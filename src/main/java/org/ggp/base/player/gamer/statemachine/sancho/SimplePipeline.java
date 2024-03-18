package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

import com.lmax.disruptor.Sequence;

/**
 * Highly efficient, lock-free rollout request pipeline with a single consumer.
 */
public class SimplePipeline
{
  /**
   * Maximum size of the pipeline and mask for computing index into the underlying array.
   */
  private final int mSize;
  private final int mIndexMask;

  /**
   * The array holding the requests.
   */
  private final RolloutRequest[] mStore;

  /**
   * The last request which has been published following select/expand processing.
   *
   *  Written by: Tree thread.
   *  Read by:    Tree thread, Rollout thread.
   */
  private final Sequence mLastExpanded = new Sequence();
  private long mLastExpandedCache = -1;

  /**
   * The last request which has been rolled out.
   *
   * Written by: Rollout thread.
   * Read by:    Tree thread.
   */
  private final Sequence mLastRolledOut = new Sequence();
  private long mLastRolledOutCache = -1;

  /**
   * The last request which has been completed by the back-propagation processing.
   *
   * Written by: Tree thread.
   * Read by:    Tree thread.
   */
  private long mLastBackPropagated = -1L;

  /**
   * Create a pipeline with the specified maximum size.
   *
   * @param xiSize - the maximum number of objects that can be in the pipeline.
   * @param xiNumRoles number of roles in the game
   * @param underlyingStateMachine state machine of the game
   */
  public SimplePipeline(int xiSize, int xiNumRoles, ForwardDeadReckonPropnetStateMachine underlyingStateMachine)
  {
    assert(Integer.bitCount(xiSize) == 1) : "Store size must be a power of 2 and > 0";

    mSize = xiSize;
    mIndexMask = xiSize - 1;

    // Create the backing store.
    mStore = new RolloutRequest[mSize];
    for (int lii = 0; lii < mSize; lii++)
    {
      mStore[lii] = new RolloutRequest(xiNumRoles, underlyingStateMachine);
    }
  }

  /**
   * @return whether the tree thread can perform an expansion now.
   *
   * Called by the tree thread only.
   */
  public boolean canExpand()
  {
    return (mLastExpandedCache < mLastBackPropagated + mSize);
  }

  /**
   * @return a blank rollout request to be filled in.
   *
   * The caller must ensure (or know) that {@link #canExpand()} is true before calling this method.  After calling
   * this method, the caller MUST call {@link #expandComplete()}.
   *
   * Called by the tree thread only.
   */
  public RolloutRequest getNextExpandSlot()
  {
    assert(canExpand()) : "Ensure canExpand() before calling getNextExpandSlot()";
    return mStore[(int)(mLastExpandedCache + 1) & mIndexMask];
  }

  /**
   * Publish a rollout request for further processing.
   *
   * Called by the tree thread only.
   */
  public void expandComplete()
  {
    assert(canExpand()) : "Call getNextExpandSlot() before calling expandComplete()";
    mLastExpandedCache++;
    mLastExpanded.setVolatile(mLastExpandedCache);
  }

  /**
   * Get the next rollout request.
   *
   * This method will not return until a rollout request is available.
   *
   * @return the next rollout request.
   *
   * Called by the rollout thread only.
   */
  public RolloutRequest getNextRolloutRequest()
  {
    final long lNextRequestID = mLastRolledOutCache + 1;

    // Try a non-volatile read first, for speed.
    if (mLastExpandedCache >= lNextRequestID)
    {
      return mStore[(int)lNextRequestID & mIndexMask];
    }

    final Thread lThread = Thread.currentThread();

    // Perform volatile reads until the next request is available.  (The cached value will be automatically updated
    // because get() is a volatile read, which fetches everything that happened before, including updating the cache
    // value.  Furthermore, this thread isn't allowed to write to the cached value.)
    while ((!lThread.isInterrupted()) && (mLastExpanded.get() < lNextRequestID))
    {
      Thread.yield();
    }

    return mStore[(int)lNextRequestID & mIndexMask];
  }

  /**
   * Mark a rollout as complete.
   *
   * Called by the rollout thread only.
   */
  public void rolloutComplete()
  {
    mLastRolledOutCache++;
    mLastRolledOut.setVolatile(mLastRolledOutCache);
  }

  /**
   * @return whether there are any completed rollout requests ready for back-propagation.
   *
   * Called by the tree thread only.
   */
  public boolean canBackPropagate()
  {
    // See if we already know that back-propagation will be successful.
    if (mLastRolledOutCache > mLastBackPropagated)
    {
      return true;
    }

    if (isEmpty())
    {
      return false;
    }

    // Perform a volatile read.  This will also update the cached value (because of volatile semantics).  We're not
    // allowed to write to the cached value anyway.
    return (mLastRolledOut.get() > mLastBackPropagated);
  }

  /**
   * @return the next rollout request to be back-propagated.
   *
   * This method will not return until a rollout request is available.
   *
   * Called by the tree thread only.
   */
  public RolloutRequest getNextRequestForBackPropagation()
  {
    final long lNextRequestID = mLastBackPropagated + 1;

    // See if we already know that a request is available.
    if (mLastRolledOutCache >= lNextRequestID)
    {
      return mStore[(int)lNextRequestID & mIndexMask];
    }

    // Perform volatile reads until the next request is available.  This will also update the cached value (because of
    // volatile semantics).  We're not allowed to write to the cached value anyway.
    while (mLastRolledOut.get() < lNextRequestID)
    {
      Thread.yield();
    }

    return mStore[(int)lNextRequestID & mIndexMask];
  }

  /**
   * Mark that back-propagation of a request is complete.
   *
   * Called by the tree thread only.
   */
  public void backPropagationComplete()
  {
    mLastBackPropagated++;
  }

  /**
   * @return whether the pipeline is completely empty.
   *
   * Called by the tree thread only.
   */
  public boolean isEmpty()
  {
    return mLastExpandedCache == mLastBackPropagated;
  }
}
