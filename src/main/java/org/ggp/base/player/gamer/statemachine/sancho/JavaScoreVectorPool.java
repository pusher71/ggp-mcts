package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 * To avoid individual allocation of small score vectors in every node
 * we allocate via one large pooled array with logical access to
 * per-role values for average and average squared score
 */
public class JavaScoreVectorPool implements ScoreVectorPool
{
  private final double[]  scoreArray;
  private final int       numRoles;
  private final int       instanceSize;

  /**
   * Construct pooled score vector
   * @param numInstances  Number of separate uses (maps directly to nodes currently)
   * @param numGameRoles  Number of roles in the game
   */
  public JavaScoreVectorPool(int numInstances, int numGameRoles)
  {
    numRoles = numGameRoles;
    instanceSize = numRoles*2;  //  average and squared scores
    scoreArray = new double[numInstances*instanceSize];
  }

  @Override
  public void setAverageScore(int instanceId, int roleIndex, double value)
  {
    scoreArray[instanceId*instanceSize + roleIndex] = value;
  }

  @Override
  public double getAverageScore(int instanceId, int roleIndex)
  {
    return scoreArray[instanceId*instanceSize + roleIndex];
  }

  @Override
  public void setAverageSquaredScore(int instanceId, int roleIndex, double value)
  {
    scoreArray[instanceId*instanceSize + roleIndex + numRoles] = value;
  }

  @Override
  public double getAverageSquaredScore(int instanceId, int roleIndex)
  {
    return scoreArray[instanceId*instanceSize + roleIndex + numRoles];
  }

  @Override
  public void terminate()
  {
    // Nothing to do.
  }
}
