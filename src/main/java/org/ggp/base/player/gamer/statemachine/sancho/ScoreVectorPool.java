package org.ggp.base.player.gamer.statemachine.sancho;

public interface ScoreVectorPool
{
  /**
   * Set an average score value
   * @param instanceId  Instance to set the member value for
   * @param roleIndex   Role value pertains to
   * @param value       Value to set
   */
  public abstract void setAverageScore(int instanceId,
                                       int roleIndex,
                                       double value);

  /**
   * Get an average score value
   * @param instanceId  Instance to get the member value for
   * @param roleIndex   Role value pertains to
   * @return average score for the specified role in the specified instance
   */
  public abstract double getAverageScore(int instanceId, int roleIndex);

  /**
   * Set an average squared score value
   * @param instanceId  Instance to set the member value for
   * @param roleIndex   Role value pertains to
   * @param value       Value to set
   */
  public abstract void setAverageSquaredScore(int instanceId,
                                              int roleIndex,
                                              double value);

  /**
   * Get an average squared score value
   * @param instanceId  Instance to get the member value for
   * @param roleIndex   Role value pertains to
   * @return average squared score for the specified role in the specified instance
   */
  public abstract double getAverageSquaredScore(int instanceId, int roleIndex);

  /**
   * Terminate the pool.  No other methods will be called after this.
   */
  public abstract void terminate();

}