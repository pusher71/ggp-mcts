package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 *  Implements SampleAverage for geometric means
 */
public class SampleAverageGeometricMean implements SampleAverage
{
  private double  averageLog = 0;
  private int     numSamples = 0;

  /**
   * Accrue a sample into the average
   * @param value
   */
  @Override
  public void addSample(double value)
  {
    averageLog = (averageLog*numSamples + Math.log(value))/(numSamples+1);
    numSamples++;
  }

  /**
   * Clear the sampled data
   */
  @Override
  public void clear()
  {
    averageLog = 0;
    numSamples = 0;
  }

  /**
   * Getter
   * @return current mean sampled value
   */
  @Override
  public double getAverage()
  {
    return Math.exp(averageLog);
  }
}
