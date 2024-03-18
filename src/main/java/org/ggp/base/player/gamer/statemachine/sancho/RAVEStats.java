package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;

/**
 * RAVE statistics.
 */
public class RAVEStats
{
  /**
   * Per-child visit count.
   */
  public final int[] mCounts;

  /**
   * Per-child score.
   */
  public final float[] mScores;

  /**
   * Create a new set of RAVE statistics.
   *
   * @param xiSize - the number of slots required.
   */
  public RAVEStats(int xiSize)
  {
    mCounts = new int[xiSize];
    mScores = new float[xiSize];
  }

  /**
   * Allocator for RAVE statistics, for pooled use.
   */
  public static class RAVEStatsAllocator implements ObjectAllocator<RAVEStats>
  {
    private final RuntimeGameCharacteristics mCharacteristics;

    /**
     * Create an allocator for RAVE statistics.
     *
     * @param xiCharacteristics - Game characteristics, from which the choice high-water mark can be retrieved.
     */
    public RAVEStatsAllocator(RuntimeGameCharacteristics xiCharacteristics)
    {
      mCharacteristics = xiCharacteristics;
    }

    @Override
    public RAVEStats newObject(int xiPoolIndex)
    {
      int lSize = mCharacteristics.getChoicesHighWaterMark(0);
      RAVEStats lStats = new RAVEStats(lSize);
      return lStats;
    }

    @Override
    public void resetObject(RAVEStats xiStats, boolean xiFree)
    {
      for (int lii = 0; lii < xiStats.mCounts.length; lii++)
      {
        xiStats.mCounts[lii] = 0;
        xiStats.mScores[lii] = 0;
      }
    }

    @Override
    public boolean shouldReset(RAVEStats xiObject)
    {
      assert(false) : "Shouldn't call shouldReset(RAVEStats)";
      return false;
    }
  }
}
