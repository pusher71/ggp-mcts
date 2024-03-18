package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 *  Re-usable interface to abstract the averaging of a sampled value.  Support
 *  arbitrry type of average via its implementations
 */
public interface SampleAverage
{
  /**
   * Accrue a sample into the average
   * @param value
   */
  public abstract void addSample(double value);
  /**
   * Clear the sampled data
   */
  public abstract void clear();

  /**
   * Getter
   * @return current mean sampled value
   */
  public abstract double getAverage();
}
