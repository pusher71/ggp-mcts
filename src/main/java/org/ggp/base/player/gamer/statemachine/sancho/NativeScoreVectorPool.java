package org.ggp.base.player.gamer.statemachine.sancho;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

public class NativeScoreVectorPool implements ScoreVectorPool
{
  private static final Unsafe mUnsafe = getUnsafe();
  private long mScores;
  private final int  mNumRoles;
  private final int  mInstanceSize;

  /**
   * Construct pooled score vector using Unsafe arrays.
   * @param xiNumInstances  Number of separate uses (maps directly to nodes currently)
   * @param xiNumRoles  Number of roles in the game
   */
  public NativeScoreVectorPool(int xiNumInstances, int xiNumRoles)
  {
    mNumRoles = xiNumRoles;
    mInstanceSize = mNumRoles * 2;
    mScores = mUnsafe.allocateMemory(xiNumInstances * mInstanceSize * Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
  }

  @Override
  public void setAverageScore(int xiInstanceId, int xiRoleIndex, double xiValue)
  {
    mUnsafe.putDouble(mScores + (Unsafe.ARRAY_DOUBLE_INDEX_SCALE * (xiInstanceId * mInstanceSize + xiRoleIndex)),
                      xiValue);
  }

  @Override
  public double getAverageScore(int xiInstanceId, int xiRoleIndex)
  {
    return mUnsafe.getDouble(mScores + (Unsafe.ARRAY_DOUBLE_INDEX_SCALE * (xiInstanceId * mInstanceSize + xiRoleIndex)));
  }

  @Override
  public void setAverageSquaredScore(int xiInstanceId,
                                     int xiRoleIndex,
                                     double xiValue)
  {
    mUnsafe.putDouble(mScores + (Unsafe.ARRAY_DOUBLE_INDEX_SCALE * (xiInstanceId * mInstanceSize + xiRoleIndex + mNumRoles)),
                      xiValue);
  }

  @Override
  public double getAverageSquaredScore(int xiInstanceId, int xiRoleIndex)
  {
    return mUnsafe.getDouble(mScores + (Unsafe.ARRAY_DOUBLE_INDEX_SCALE *
                                      (xiInstanceId * mInstanceSize + xiRoleIndex + mNumRoles)));
  }

  @Override
  public void terminate()
  {
    if (mScores != 0)
    {
      mUnsafe.freeMemory(mScores);
      mScores = 0;
    }
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
}
