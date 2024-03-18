package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.PrintStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class to log stdout and stderr to the log file.
 */
public class StdOutErrLog
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * Ensure that everything written via System.out and System.err makes it to the logs.  (Really, components should
   * use their own LOGGER instance, because that also logs the calling site and allows control over log levels.)
   */
  public static void tieSystemOutAndErrToLog()
  {
    System.setOut(createLoggingProxy(System.out));
    System.setErr(createLoggingProxy(System.err));
  }

  @SuppressWarnings("synthetic-access")
  private static PrintStream createLoggingProxy(final PrintStream realPrintStream)
  {
    return new PrintStream(realPrintStream)
    {
      @Override
      public void print(final String string)
      {
        realPrintStream.print(string);
        LOGGER.info(string);
      }

      @Override
      public void println(final String string)
      {
        realPrintStream.println(string);
        LOGGER.warn(string);
      }
    };
  }
}