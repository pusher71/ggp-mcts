package org.ggp.base.player.gamer.statemachine.sancho;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;

/**
 * Class for logging of system statistics.
 */
public class SystemStatsLogger implements Runnable
{
  private static final Logger STATS_LOGGER = LogManager.getLogger("stats");

  private static long                         INTERVAL    = 1000;
  private static MemoryMXBean                 MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
  private static List<GarbageCollectorMXBean> GC_BEANS    = ManagementFactory.getGarbageCollectorMXBeans();

  private final Thread mThread;
  private final String mLogName;

  /**
   * Create a statistics logger.
   *
   * All statistics loggers MUST be stopped by calling {@link #stop()} to dispose of thread resources.
   *
   * @param xiLogName - the name of the log.
   */
  public SystemStatsLogger(String xiLogName)
  {
    mLogName = xiLogName;

    mThread = new Thread(this, "SystemStatsLogger");
    mThread.setDaemon(true);
    mThread.start();
  }

  @Override
  public void run()
  {
    ThreadContext.put("matchID", mLogName);

    long lNextLogTime = System.currentTimeMillis() + INTERVAL;
    boolean lInterrupted = false;
    while (!lInterrupted)
    {
      // Wait until the next time to make a stats log (or until we're interrupted).
      for (long lNow = System.currentTimeMillis();
           lNow < lNextLogTime;
           lNow = System.currentTimeMillis())
      {
        try
        {
          Thread.sleep(lNextLogTime - lNow);
        }
        catch (InterruptedException lEx)
        {
          lInterrupted = true;
          break;
        }
      }

      // Make stats logs
      makeStatsLog();

      // Set the next stats log time - based on the last time, not the time now.
      lNextLogTime += INTERVAL;
    }
  }

  /**
   * Dump system statistics.
   */
  private static void makeStatsLog()
  {
    long lNow = System.currentTimeMillis();
    MemoryUsage lMemUsage = MEMORY_BEAN.getHeapMemoryUsage();

    long lGCTime = 0;
    long lGCCount = 0;

    for (GarbageCollectorMXBean lGCBean : GC_BEANS)
    {
      lGCTime  += lGCBean.getCollectionTime();
      lGCCount += lGCBean.getCollectionCount();
    }

    StringBuffer lLogBuf = new StringBuffer(1024);
    Series.MEM_USED.logDataPoint(lLogBuf, lNow, lMemUsage.getUsed());
    Series.MEM_ALLOC_RATE.logDataPoint(lLogBuf, lNow, lMemUsage.getUsed() / 100);
    Series.MEM_COMMITTED.logDataPoint(lLogBuf, lNow, lMemUsage.getCommitted());
    Series.MEM_MAX.logDataPoint(lLogBuf, lNow, lMemUsage.getMax());

    Series.GC_TIME.logDataPoint(lLogBuf, lNow, lGCTime);
    Series.GC_COUNT.logDataPoint(lLogBuf, lNow, lGCCount);

    STATS_LOGGER.info(lLogBuf.toString());
  }

  /**
   * Stop the statistics logger.  After stopping, the statistics logger cannot be restarted.
   */
  public void stop()
  {
    mThread.interrupt();
    try
    {
      mThread.join(5000);
    }
    catch (InterruptedException lEx)
    {
      STATS_LOGGER.warn("Interrupted whilst waiting for StatsLogger to stop");
    }

    if (mThread.isAlive())
    {
      STATS_LOGGER.error("Failed to stop stats logging thread");
    }
  }
}
