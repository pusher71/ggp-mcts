package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * Immutable performance statistics from a rollout thread.
 */
public class RolloutPerfStats
{
  /**
   * The amount of useful work performed, in nanoseconds.
   */
  public final long mUsefulWork;

  /**
   * The time for which the thread was blocked on the pipeline, in nanoseconds.
   */
  public final long mBlockedFor;

  /**
   * The fraction of the total that useful work accounts for, in the range 0-1.
   */
  public final double mUsefulWorkFraction;

  /**
   * Create rollout performance statistics.
   *
   * @param xiUsefulWork - the amount of useful work done (in nanoseconds).
   * @param xiBlockedFor - the amount of time the thread was blocked for (in nanoseconds).
   */
  public RolloutPerfStats(long xiUsefulWork, long xiBlockedFor)
  {
    assert(xiUsefulWork >= 0) : "xiUsefulWork was negative: " + xiUsefulWork;
    assert(xiBlockedFor >= 0) : "xiBlockedFor was negative: " + xiBlockedFor;

    mUsefulWork = xiUsefulWork;
    mBlockedFor = xiBlockedFor;

    long lTotalWork = xiUsefulWork + xiBlockedFor;
    if (lTotalWork == 0)
    {
      mUsefulWorkFraction = 1;
    }
    else
    {
      mUsefulWorkFraction = ((double)xiUsefulWork / (double)lTotalWork);
    }
  }

  /**
   * Create a set of rollout performance statistics by combining other sets of statistics.
   *
   * @param xiStats - several sets of performance statistics (from the different rollout threads).
   */
  public RolloutPerfStats(RolloutPerfStats[] xiStats)
  {
    long lUsefulWork = 0;
    long lBlockedFor = 0;

    for (RolloutPerfStats lStats : xiStats)
    {
      if ( lStats != null )
      {
        lUsefulWork += lStats.mUsefulWork;
        lBlockedFor += lStats.mBlockedFor;
      }
    }

    mUsefulWork = lUsefulWork;
    mBlockedFor = lBlockedFor;

    long lTotalWork = lUsefulWork + lBlockedFor;
    if (lTotalWork == 0)
    {
      mUsefulWorkFraction = 1;
    }
    else
    {
      mUsefulWorkFraction = ((double)lUsefulWork / (double)lTotalWork);
    }
  }

  /**
   * @return the difference between this set of statistics and a previous set.
   *
   * @param xiPrevious - the previous statistics.
   */
  public RolloutPerfStats getDifference(RolloutPerfStats xiPrevious)
  {
    assert(mUsefulWork >= xiPrevious.mUsefulWork) : "Useful-work has gone backwards";
    assert(mBlockedFor >= xiPrevious.mBlockedFor) : "Blocked-for has gone backwards";

    return new RolloutPerfStats(mUsefulWork - xiPrevious.mUsefulWork,
                                mBlockedFor - xiPrevious.mBlockedFor);
  }
}
