package org.ggp.base.player.gamer.statemachine.sancho.pool;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import sun.misc.Unsafe;

/**
 * A fixed size pool using native memory management.
 *
 * @param <ItemType> the type of item to be kept in the pool.
 */
public class UnsafePool<ItemType> implements Pool<ItemType>
{
  // Access to unsafe (native memory management) methods.
  private final Unsafe                                 mUnsafe;

  // Address of the backing store (which is just a malloc'd blob).
  private long                                         mStore;

  // Indirect access.
  private final Pointer                                mPointer;
  private final long                                   mPointerOffset;

  // Pool capacity (number of items).
  private final int                                    mCapacity;

  // Size of each item in the pool.
  private final long                                   mItemSize;

  // Number of free entries required for isFull() to return false
  private int                                          mFreeThresholdForNonFull;

  // Indexes of items that are free for reuse.
  private final int[]                                  mFreeSlots;

  // The number of items currently is use.
  private int                                          mNumItemsInUse = 0;

  /**
   * Create a new pool of the specified maximum size.
   *
   * @param xiPoolSize - the pool size.
   */
  public UnsafePool(Class<?> xiClass, int xiPoolSize)
  {
    // Get access to native memory management.
    mUnsafe = getUnsafe();

    mPointer = new Pointer();
    try
    {
      mPointerOffset = mUnsafe.objectFieldOffset(Pointer.class.getDeclaredField("mPointer"));
    }
    catch (Exception lEx)
    {
      throw new RuntimeException(lEx);
    }

    // Store parameters for later use.
    mCapacity = xiPoolSize;
    mItemSize = sizeof(xiClass);

    // Create the list of free slots.
    mFreeSlots = new int[xiPoolSize];
    for (int lii = 0; lii < mCapacity; lii++)
    {
      mFreeSlots[lii] = lii;
    }

    mFreeThresholdForNonFull = xiPoolSize / 100;  //  Default to 1% free
  }

  /**
   * Get access to unsafe memory management function.
   *
   * @return the Unsafe object.
   */
  private static Unsafe getUnsafe()
  {
    try
    {
      Field lField = Unsafe.class.getDeclaredField("theUnsafe");
      lField.setAccessible(true);
      return (Unsafe)lField.get(null);
    }
    catch (Exception lEx)
    {
      throw new RuntimeException(lEx);
    }
  }

  public long sizeof(Class<?> xiClass)
  {
    // Get the field with the largest offset in the class or any of its superclasses.
    long lMaxOffset = 0;
    do
    {
      for (Field lField : xiClass.getDeclaredFields())
      {
        if (!Modifier.isStatic(lField.getModifiers()))
        {
          lMaxOffset = Math.max(lMaxOffset, mUnsafe.objectFieldOffset(lField));
        }
      }
    } while ((xiClass = xiClass.getSuperclass()) != null);

    // No Java field is larger than 8 bytes.  Play it safe and add 8 bytes.
    return lMaxOffset + 8;
  }

  static class Pointer
  {
    public Object mPointer;
  }

  @Override
  public void setNonFreeThreshold(int threshold)
  {
    if ( mFreeThresholdForNonFull < threshold )
    {
      mFreeThresholdForNonFull = threshold;
    }
  }

  @Override
  public int getCapacity()
  {
    return mCapacity;
  }

  @Override
  public int getNumItemsInUse()
  {
    return mNumItemsInUse;
  }

  @Override
  public int getPoolUsage()
  {
    return mNumItemsInUse * 100 / mCapacity;
  }

  @Override
  public ItemType allocate(ObjectAllocator<ItemType> xiAllocator)
  {
    assert(mNumItemsInUse < mCapacity) : "Pool unexpectedly full";

    // On first allocation, create the backing store.
    if (mStore == 0)
    {
      // Allocate memory for the backing store.
      mStore = mUnsafe.allocateMemory(mCapacity * mItemSize);

      // Create a prototype object which will be used to initialize all objects in the backing store.
      ItemType lPrototype = xiAllocator.newObject(0);

      for (int lii = 0; lii < mCapacity; lii++)
      {
        mUnsafe.copyMemory(lPrototype, 0, null, mStore + (lii * mItemSize), mItemSize);
      }
    }

    ItemType lAllocatedItem = get(mFreeSlots[mNumItemsInUse++]);
    xiAllocator.resetObject(lAllocatedItem, false);
    return lAllocatedItem;
  }

  @Override
  @SuppressWarnings("unchecked")
  public ItemType get(int xiIndex)
  {
    mUnsafe.putLong(mPointer, mPointerOffset, mStore + (xiIndex * mItemSize));
    return (ItemType)mPointer.mPointer;
  }

  @Override
  public void free(ItemType xiItem, int xiIndex)
  {
    assert(mNumItemsInUse > 0);
    mFreeSlots[--mNumItemsInUse] = xiIndex;
  }

  @Override
  public boolean isFull()
  {
    return (mNumItemsInUse > mCapacity - mFreeThresholdForNonFull);
  }

  @Override
  public void clear(ObjectAllocator<ItemType> xiAllocator, boolean xiFilter)
  {
    if (mStore == 0)
    {
      return;
    }

    if (!xiFilter)
    {
      // This is called during meta-gaming when we've finished doing the true meta-gaming tasks and are about to kick
      // off the regular search.

      // Reset every object and reset the free list.
      for (int lii = 0; lii < mCapacity; lii++)
      {
        xiAllocator.resetObject(get(lii), true);
        mFreeSlots[lii] = lii;
      }

      mNumItemsInUse = 0;
    }
    else
    {
      // This is called at the start of a turn when the new root state isn't to be found in one of the trees (i.e. only
      // if things have gone badly wrong).

      for (int lii = 0; lii < mCapacity; lii++)
      {
        // Just reset the items that match the filter.
        ItemType lItem = get(lii);
        if (xiAllocator.shouldReset(lItem))
        {
          xiAllocator.resetObject(lItem, true);
          mFreeSlots[--mNumItemsInUse] = lii;
          mNumItemsInUse--;
        }
      }
    }
  }
}
