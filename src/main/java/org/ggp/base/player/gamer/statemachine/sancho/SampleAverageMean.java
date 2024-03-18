package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 *  Implements SampleAverage for arithmetic means
 */
public class SampleAverageMean implements SampleAverage
{
  private double  average = 0;
  private int     numSamples = 0;

  /**
   * Accrue a sample into the average
   * @param value
   */
  @Override
  public void addSample(double value)
  {
    average = (average*numSamples + value)/(numSamples+1);
    numSamples++;
  }

  /**
   * Clear the sampled data
   */
  @Override
  public void clear()
  {
    average = 0;
    numSamples = 0;
  }

  /**
   * Getter
   * @return current mean sampled value
   */
  @Override
  public double getAverage()
  {
    return average;
  }
}
