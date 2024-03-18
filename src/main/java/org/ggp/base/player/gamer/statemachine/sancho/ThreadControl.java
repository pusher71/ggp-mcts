package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.DWORD;

/**
 * Utility class for controlling threading behaviour, including processor affinity.
 *
 * There are two classes of CPU intensive thread for Sancho.
 *
 * - GameSearcher     - there's always a single instance of this thread.
 * - RolloutProcessor - there can be several of these.
 *
 * These can be bound to vCPUs if desired because on some systems it increases performance.
 *
 * Note that this class contains Windows-specific code.  It would be straightforward to extend to Linux and POSIX
 * systems if required.
 */
public class ThreadControl
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * The number of vCPUs available on the system.  (For a hyper-threaded system, each hyper-thread counts as a CPU.)
   */
  public static final int NUM_CPUS = Runtime.getRuntime().availableProcessors();

  /**
   * Whether to perform rollouts synchronously from the search thread.  Used to disable threading for debugging.
   */
  public static final boolean RUN_SYNCHRONOUSLY = false;

  /**
   * The number of CPU-intensive threads.
   *
   * Unless configured otherwise, use half the available vCPUs.
   */
  public static int CPU_INTENSIVE_THREADS;
  static
  {
    if (RUN_SYNCHRONOUSLY)
    {
      // When running synchronously, there's just the 1 CPU-intensive thread.
      CPU_INTENSIVE_THREADS = 1;
    }
    else
    {
      // Get the configured value.
      int lConfiguredValue = MachineSpecificConfiguration.getCfgInt(CfgItem.CPU_INTENSIVE_THREADS);
      if (lConfiguredValue == -1)
      {
        // No configured value - calculate a default.  Use half the available vCPUs.
        CPU_INTENSIVE_THREADS = (NUM_CPUS + 1) / 2;
      }
      else
      {
        // Use the configured value.
        CPU_INTENSIVE_THREADS = lConfiguredValue;
      }
    }
  }

  /**
   * The number of rollout threads.  There's 1 for the GameSearcher thread and the rest are for rollouts.
   */
  public static int ROLLOUT_THREADS = CPU_INTENSIVE_THREADS - 1;

  /**
   * Whether to pin the CPU intensive threads to fixed cores to prevent core thrashing.
   */
  private static boolean USE_AFFINITY_MAPPING = MachineSpecificConfiguration.getCfgBool(CfgItem.USE_AFFINITY);

  /**
   * On hyper-threaded CPUs we get far better performance allocating every other logical core
   * and so placing our threads on separate physical cores - always do this if we're only using half the
   * logical cores anyway.
   */
  private static int CPU_STRIPE_STRIDE = ((NUM_CPUS + 1) / 2 >= CPU_INTENSIVE_THREADS) ? 2 : 1;

  /**
   * A parity which influences whether logical CPU striping is done on odd or even parity CPU IDs.  Particularly useful
   * when running more than 1 instance for test purposes.  Based on the port number of the player instance.
   */
  public static boolean sCPUIdParity = false;

  /**
   * vCPU index of the last registered search thread.  (Wraps at NUM_CPUS.)
   */
  private static int sNextSearchThreadCPUIndex = 0;

  /**
   * vCPU index of the last registered rollout thread.  (Wraps at NUM_CPUS.)
   */
  private static int sNextRolloutThreadCPUIndex = CPU_STRIPE_STRIDE;

  private static volatile Thread sTreeOwner;

  private ThreadControl()
  {
    // Private default constructor.
  }

  /**
   * Reset the allocation cursors for CPU id to original values - should be used
   * when destroying the current consuming threads.
   */
  public static void reset()
  {
    sNextSearchThreadCPUIndex = (sCPUIdParity ? 1 : 0);
    sNextRolloutThreadCPUIndex = (CPU_STRIPE_STRIDE + (sCPUIdParity ? 1 : 0)) % NUM_CPUS;
  }

  /**
   * Free resources at the end of a game to make them eligible for GC.
   */
  public static void tidyUp()
  {
    sTreeOwner = null;
  }

  /**
   * Register a game search thread.
   */
  public static void registerSearchThread()
  {
    if (USE_AFFINITY_MAPPING)
    {
      synchronized (ThreadControl.class)
      {
        // Bind this thread to the selected virtual CPU.
        ThreadControl.setThreadAffinity(1 << sNextSearchThreadCPUIndex);
        LOGGER.info("  Bound search thread to vCPU:  " + sNextSearchThreadCPUIndex);

        // Calculate the next available virtual CPU for search threads.
        // If we're striping rollout threads with a stride greater than 1 then
        // then we can interleave the searchers.  If the rollout stride is 1 anyway
        // anything will clash so this is still as good a policy as any
        if (sNextSearchThreadCPUIndex == (sCPUIdParity ? 1 : 0))
        {
          sNextSearchThreadCPUIndex = CPU_STRIPE_STRIDE + 1 + (sCPUIdParity ? 1 : 0);
        }
        else
        {
          sNextSearchThreadCPUIndex += CPU_STRIPE_STRIDE;
        }
        sNextSearchThreadCPUIndex = sNextSearchThreadCPUIndex % NUM_CPUS;
      }
    }
  }

  /**
   * Take ownership of the tree.  Only permitted when no other thread owns the tree.
   *
   * @return true, always.
   */
  public static boolean takeTreeOwnership()
  {
    assert(sTreeOwner == null) :
          Thread.currentThread().getName() + " can't take tree ownership because it's owned by " + sTreeOwner.getName();
    sTreeOwner = Thread.currentThread();
    return true;
  }

  /**
   * Release tree ownership.  Can only be called by the current tree owner.
   *
   * @return true, always.
   */
  public static boolean releaseTreeOwnership()
  {
    assert(sTreeOwner == Thread.currentThread()) :
       Thread.currentThread().getName() + " can't release tree ownership because it's owned by " + sTreeOwner.getName();
    sTreeOwner = null;
    return true;
  }

  /**
   * Check that the calling thread is the tree owner.
   *
   * @return true, always.
   */
  public static boolean checkTreeOwnership()
  {
    assert(sTreeOwner == Thread.currentThread()) :
                  Thread.currentThread().getName() + " can't modify tree because it's owned by " + sTreeOwner.getName();
    return true;
  }

  /**
   * Release tree ownership.  Must only be called when there's a problem (e.g. when ownership state is unknown
   * following an exception).
   *
   * @return true, always.
   */
  public static boolean abortTreeOwnership()
  {
    if (sTreeOwner == Thread.currentThread())
    {
      sTreeOwner = null;
    }
    return true;
  }

  /**
   * Register a rollout thread.
   */
  public static void registerRolloutThread()
  {
    if (USE_AFFINITY_MAPPING)
    {
      synchronized (ThreadControl.class)
      {
        // Bind this thread to the selected virtual CPU.
        ThreadControl.setThreadAffinity(1 << sNextRolloutThreadCPUIndex);
        LOGGER.info("  Bound rollout processor to vCPU: " + sNextRolloutThreadCPUIndex);

        // Calculate the next available virtual CPU for rollout threads, wrapping as required and leaving a gap for the
        // search thread if required.
        if (sNextRolloutThreadCPUIndex == (sCPUIdParity ? 1 : 0))
        {
          sNextRolloutThreadCPUIndex = CPU_STRIPE_STRIDE + (sCPUIdParity ? 1 : 0);
        }
        else
        {
          sNextRolloutThreadCPUIndex += CPU_STRIPE_STRIDE;
        }
        sNextRolloutThreadCPUIndex = sNextRolloutThreadCPUIndex % NUM_CPUS;
      }
    }
  }

  /**
   * JNA access to kernel32.dll.
   */
  private interface Kernel32Library extends Library
  {
    public static final Kernel32Library INSTANCE = (Kernel32Library)Native.loadLibrary("kernel32", Kernel32Library.class);
    public void SetThreadAffinityMask(final int pid, final DWORD lpProcessAffinityMask) throws LastErrorException;
    public int GetCurrentThread() throws LastErrorException;
  }

  /**
   * Set CPU affinity for the current thread.
   *
   * @param xiAffinityMask - the affinity mask.
   */
  private static void setThreadAffinity(final long xiAffinityMask)
  {
    DWORD lAffinityMask = new DWORD(xiAffinityMask);
    try
    {
      Kernel32Library.INSTANCE.SetThreadAffinityMask(Kernel32Library.INSTANCE.GetCurrentThread(), lAffinityMask);
    }
    catch (Throwable lEx)
    {
      // On non-Windows platforms, we'll never manage to load the library.  For now, just log an error.
      LOGGER.error("Failed to set thread affinity: err=", lEx);
    }
  }

  /**
   * Override thread-counts that should normally be considered final.  For use in UT only.
   *
   * @param xiNumThreads - the number of CPU-intensive threads.
   */
  public static void utOverrideCPUIntensiveThreads(int xiNumThreads)
  {
    CPU_INTENSIVE_THREADS = xiNumThreads;
    ROLLOUT_THREADS = CPU_INTENSIVE_THREADS - 1;
    USE_AFFINITY_MAPPING = false;
  }
}