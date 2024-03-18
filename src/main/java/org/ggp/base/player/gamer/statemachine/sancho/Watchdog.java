package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Watchdog class that will notify an expiry handler if it stops receiving liveness kicks.
 */
public class Watchdog implements Runnable
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * Interface implemented by objects which can handle watchdog expiration.
   */
  public interface WatchdogExpiryHandler
  {
    /**
     * Callback made when the watchdog expires.
     */
    public void expired();
  }

  private WatchdogExpiryHandler mExpiryHandler;
  private final long mInterval;

  private volatile long mExpiryTime;

  private Thread mThread;

  private volatile boolean mContinue;
  private volatile boolean mExpired;

  /**
   * Create a watchdog.
   *
   * @param xiInterval - the interval after which the watchdog expires.
   * @param xiExpiryHandler - the victim.
   */
  public Watchdog(long xiInterval, WatchdogExpiryHandler xiExpiryHandler)
  {
    // Save off the provided values.
    mInterval = xiInterval;
    mExpiryHandler = xiExpiryHandler;

    // Get everything ready to start the watchdog.
    mContinue = true;
    mExpired = false;
    alive();

    // Spawn the watchdog thread.
    mThread = new Thread(this, "Watchdog");
    mThread.setDaemon(true);
    mThread.start();
  }

  /**
   * Notify the watchdog that the victim is still alive.
   */
  public void alive()
  {
    long lExpireTime = System.currentTimeMillis() + mInterval;
    mExpiryTime = lExpireTime;
    LOGGER.debug("Watchdog won't fire until: " + mExpiryTime);
  }

  /**
   * Stop the watchdog.
   */
  public void stop()
  {
    LOGGER.info("Stop the watchdog");

    // If the watchdog has expired then this routine is (probably) being called on the watchdog thread.  In that case,
    // we know the watchdog is thread is going to quit and don't want to interrupt it by the processing below.
    if (mExpired)
    {
      return;
    }

    Thread lThread = mThread;

    mContinue = false;
    lThread.interrupt();
    try
    {
      lThread.join(5000);
    }
    catch (InterruptedException lEx)
    {
      LOGGER.warn("Interrupted whilst waiting for watchdog to stop");
    }

    if (lThread.isAlive())
    {
      LOGGER.error("Failed to stop watchdog");
    }
    else
    {
      LOGGER.info("Watchdog terminated");
    }
  }

  @Override
  public void run()
  {
    LOGGER.info("Starting watchdog with interval " + mInterval + "ms");

    try
    {
      while (mContinue)
      {
        long lTimeRemaining = mExpiryTime - System.currentTimeMillis();

        if (lTimeRemaining > 0)
        {
          // Not time to expire yet.  Just wait.
          try
          {
            Thread.sleep(lTimeRemaining);
          }
          catch (InterruptedException lEx)
          {
            // We'll be interrupted when the game ends - to tidy up.  If this is a spurious wake-up, we'll notice and
            // go back to sleep.
          }
        }
        else
        {
          // Watchdog has expired.  Notify the handler.
          LOGGER.warn("Watchdog liveness timer expired");
          mExpired = true;
          mExpiryHandler.expired();
          break;
        }
      }
    }
    catch (Exception lEx)
    {
      LOGGER.error("Watchdog died unexpectedly", lEx);
    }

    // Ensure that we aren't keeping any state alive.
    mExpiryHandler = null;
    mThread = null;
  }
}
