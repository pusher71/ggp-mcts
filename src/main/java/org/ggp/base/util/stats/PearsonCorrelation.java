package org.ggp.base.util.stats;

/**
 * Pearson's product-moment correlation coefficient.
 */
public class PearsonCorrelation
{
  private SampledStatistic mStat1     = new SampledStatistic();
  private SampledStatistic mStat2     = new SampledStatistic();
  private double           productSum = 0;

  /**
   * Record a sample.
   *
   * @param xiValue1 - sampled value of the first random variable.
   * @param xiValue2 - sampled value of the second random variable.
   */
  public void sample(double xiValue1, double xiValue2)
  {
    productSum += xiValue1 * xiValue2;
    mStat1.sample(xiValue1);
    mStat2.sample(xiValue2);
  }

  /**
   * @return the correlation between the two random variables.
   */
  public double getCorrelation()
  {
    assert(mStat1.getNumSamples() == mStat2.getNumSamples());
    int lNumSamples = mStat1.getNumSamples();

    double lStdDev1 = mStat1.getStdDev();
    double lStdDev2 = mStat2.getStdDev();

    if ((lStdDev1 == 0) || (lStdDev2 == 0) || (lNumSamples < 2))
    {
      return 0;
    }

    double lCorrelation = ((productSum - (lNumSamples * mStat1.getMean() * mStat2.getMean())) /
                           ((lNumSamples - 1) * lStdDev1 * lStdDev2));
    assert(Math.abs(lCorrelation) <= 1.0001) : "Invalid correlation: " + lCorrelation;

    return lCorrelation;
  }
}
