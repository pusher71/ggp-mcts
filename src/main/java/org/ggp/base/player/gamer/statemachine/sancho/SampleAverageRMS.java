package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 *  Implements SampleAverage for root mean square
 */
public class SampleAverageRMS implements SampleAverage
{
  private double  averageSquare = 0;
  private int     numSamples = 0;

  /**
   * Accrue a sample into the average
   * @param value
   */
  @Override
  public void addSample(double value)
  {
    averageSquare = (averageSquare*numSamples + value*value)/(numSamples+1);
    numSamples++;
  }

  /**
   * Clear the sampled data
   */
  @Override
  public void clear()
  {
    averageSquare = 0;
    numSamples = 0;
  }

  /**
   * Getter
   * @return current mean sampled value
   */
  @Override
  public double getAverage()
  {
    return Math.sqrt(averageSquare);
  }
}
