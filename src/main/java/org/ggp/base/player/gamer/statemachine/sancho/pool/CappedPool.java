package org.ggp.base.player.gamer.statemachine.sancho.pool;

/**
 * A pool with a fixed maximum size.
 *
 * Freed items are kept in the pool to avoid excessive object allocation.
 *
 * @param <ItemType> the type of item to be kept in the pool.
 */
public class CappedPool<ItemType> implements Pool<ItemType>
{
  // Maximum number of items to allocate.
  private final int                                    mPoolSize;

  // Number of free entries required for isFull() to return false
  private int                                          mFreeThresholdForNonFull;

  // The pool of items.
  private final ItemType[]                             mItems;

  // Items that are available for re-use.  This is a circular buffer so that it has a LIFO access pattern.
  private final ItemType[]                             mFreeItems;
  private int                                          mNumFreeItems;
  private int                                          mFirstFreeitem;

  // Array index of the largest allocated item.  Used to track whether an attempt to allocate a new item should really
  // allocate a new item (if we're not yet at the maximum) or re-use and existing item.  This can never exceed
  // mPoolSize.
  private int                                          mLargestUsedIndex = -1;

  // Statistical information about pool usage.
  //
  // - The number of items currently is use.
  private int                                          mNumItemsInUse = 0;

  /**
   * Create a new pool of the specified maximum size.
   *
   * @param xiPoolSize - the pool size.
   */
  @SuppressWarnings("unchecked")
  public CappedPool(int xiPoolSize)
  {
    mPoolSize  = xiPoolSize;
    mItems     = (ItemType[])(new Object[xiPoolSize]);
    mFreeItems = (ItemType[])(new Object[xiPoolSize]);

    mFreeThresholdForNonFull = xiPoolSize / 100;  // Default to 1% free
  }

  @Override
  public void setNonFreeThreshold(int xiThreshold)
  {
    if (mFreeThresholdForNonFull < xiThreshold)
    {
      mFreeThresholdForNonFull = xiThreshold;
    }
  }

  /**
   * @return the table of items that backs this pool.
   *
   * This is a hack which is only used for MCTSTree validation.
   */
  public ItemType[] getItemTable()
  {
    return mItems;
  }

  @Override
  public int getCapacity()
  {
    return mPoolSize;
  }

  @Override
  public int getNumItemsInUse()
  {
    return mNumItemsInUse;
  }

  @Override
  public int getPoolUsage()
  {
    return mNumItemsInUse * 100 / mPoolSize;
  }

  @Override
  public ItemType allocate(ObjectAllocator<ItemType> xiAllocator)
  {
    ItemType lAllocatedItem;

    // Prefer to re-use a node because it avoids memory allocation which is (a) slow, (b) is liable to provoke GC and
    // (c) makes GC slower because there's a bigger heap to inspect.
    if (mNumFreeItems != 0)
    {
      lAllocatedItem = mFreeItems[(mFirstFreeitem + --mNumFreeItems) % mPoolSize];

      // Reset the item so that it's ready for re-use.
      xiAllocator.resetObject(lAllocatedItem, false);
    }
    else
    {
      // No free items so allocate another one.
      assert(mLargestUsedIndex < mPoolSize - 1) : "Unexpectedly full pool";
      mLargestUsedIndex++;
      lAllocatedItem = xiAllocator.newObject(mLargestUsedIndex);
      mItems[mLargestUsedIndex] = lAllocatedItem;
    }

    mNumItemsInUse++;
    return lAllocatedItem;
  }

  @Override
  public ItemType get(int xiIndex)
  {
    return mItems[xiIndex];
  }

  @Override
  public void free(ItemType xiItem, int xiIndex)
  {
    assert(mNumItemsInUse > 0);
    mNumItemsInUse--;
    mFreeItems[(mFirstFreeitem + mNumFreeItems++) % mPoolSize] = xiItem;
  }

  @Override
  public boolean isFull()
  {
    return (mNumItemsInUse > mPoolSize - mFreeThresholdForNonFull);
  }

  @Override
  public void clear(ObjectAllocator<ItemType> xiAllocator, boolean xiFilter)
  {
    if (!xiFilter)
    {
      // This is called during meta-gaming when we've finished doing the true meta-gaming tasks and are about to kick
      // off the regular search

      // Reset every allocated object and add it to the free list.
      for (int i = 0; i <= mLargestUsedIndex; i++)
      {
        xiAllocator.resetObject(mItems[i], true);
        mFreeItems[i] = mItems[i];
      }

      mNumFreeItems = mLargestUsedIndex + 1;
      mNumItemsInUse = 0;
      mFirstFreeitem = 0;
    }
    else
    {
      // This is called at the start of a turn when the new root state isn't to be found in on of the trees (i.e. only
      // if things have gone badly wrong).

      for (int i = 0; i <= mLargestUsedIndex; i++)
      {
        // Just reset the items that match the filter.
        if (xiAllocator.shouldReset(mItems[i]))
        {
          xiAllocator.resetObject(mItems[i], true);
          mFreeItems[(mFirstFreeitem + mNumFreeItems++) % mPoolSize] = mItems[i];
          mNumItemsInUse--;
        }
      }
    }
  }
}
