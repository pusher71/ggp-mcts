package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;

public class MoveScoreInfo
{
  /**
   * Utility class for allocating tree nodes from a CappedPool.
   */
  public static class MoveScoreInfoAllocator implements ObjectAllocator<MoveScoreInfo>
  {
    private final int numRoles;

    /**
     * Create an allocator for MoveScoreInfo instances.
     *
     * @param xiNumRoles - number of roles in the game.
     */
    public MoveScoreInfoAllocator(int xiNumRoles)
    {
      numRoles = xiNumRoles;
    }

    @Override
    public MoveScoreInfo newObject(int xiPoolIndex)
    {
      MoveScoreInfo lInfo = new MoveScoreInfo(numRoles);
      return lInfo;
    }

    @Override
    public void resetObject(MoveScoreInfo xiInfo, boolean xiFree)
    {
      xiInfo.numSamples = 0;
    }

    @Override
    public boolean shouldReset(MoveScoreInfo xiNode)
    {
      return false;
    }
  }

  public final double[] averageScores;
  public int    numSamples = 0;

  public MoveScoreInfo(int numRoles)
  {
    averageScores = new double[numRoles];
  }
}