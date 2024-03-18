package org.ggp.base.util.stats;

/**
 * Keep track of a statistic based on a number of samples.
 *
 * Optimised for recording samples more often than getting the mean and/or standard deviation.
 */
public class SampledStatistic
{
  private int      mNumSamples          = 0;
  private double   mSum                 = 0;
  private double   mSumOfSquares        = 0;

  /**
   * Record a sample.
   *
   * @param xiValue - the sample value.
   */
  public void sample(double xiValue)
  {
    mSum += xiValue;
    mSumOfSquares += xiValue*xiValue;
    mNumSamples++;
  }

  /**
   * @return the number of samples recorded.
   */
  public int getNumSamples()
  {
    return mNumSamples;
  }

  /**
   * @return the total value of the recorded samples.
   */
  public long getTotal()
  {
    return (long)mSum;
  }

  /**
   * @return the mean value of the recorded samples.
   */
  public double getMean()
  {
    return mSum / mNumSamples;
  }

  /**
   * @return the standard deviation of the recorded samples.
   */
  public double getStdDev()
  {
    if (mNumSamples < 2)
    {
      return 0;
    }

    //  When the sample size gets very large and every sample is the same (TicTacToe exhibits this)
    //  then rounding errors can make the difference slightly negative, so correct for that
    //  possibility
    double numerator = Math.max(0, ((mNumSamples * mSumOfSquares) - (mSum * mSum)));
    double lStdDev = Math.sqrt( numerator/
                               (mNumSamples * ((double)mNumSamples - 1)));
    assert(!Double.isNaN(lStdDev));
    assert(!Double.isInfinite(lStdDev));
    assert(lStdDev >= 0);

    return lStdDev;
  }

  @Override
  public String toString()
  {
    if (mNumSamples == 0)
    {
      return "<No samples>";
    }

    return (long)getMean() + " +/- " + (long)getStdDev();
  }
}
