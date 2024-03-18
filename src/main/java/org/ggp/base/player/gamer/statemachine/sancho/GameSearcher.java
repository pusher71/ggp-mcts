package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.lucene.util.OpenBitSet;
import org.ggp.base.player.gamer.statemachine.sancho.LocalRegionSearcher.LocalSearchResultConsumer;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.LocalSearchStatus;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.player.gamer.statemachine.sancho.pool.CappedPool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.UncappedPool;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * The thread that searches the game tree.  This is the only thread that can make updates to the game tree except under
 * lock of {@link #getSerializationObject()}.
 */
public class GameSearcher implements Runnable, ActivityController, LocalSearchResultConsumer
{
  private static final Logger LOGGER       = LogManager.getLogger();
  private static final Logger STATS_LOGGER = LogManager.getLogger("stats");

  /**
   * Whether the sample size is updated as a result of thread performance measurements.
   */
  public static final boolean USE_DYNAMIC_SAMPLE_SIZING = true;

  /**
   * Whether to disable dynamic node trimming on full node pool (else just stall search until re-rooting)
   */
  private static final boolean DISABLE_NODE_TRIMMING =
                                           MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_NODE_TRIMMING);

  private static final long MIN_LOCAL_SEARCH_REFRESH_PERIOD = 1000;
  private static final long LOCAL_SEARCH_REVIEW_PLAYED_MOVE_TIME = 3000;

  /**
   * The update interval for sample size.
   */
  public static final long                SAMPLE_SIZE_UPDATE_INTERVAL_MS = 10000;

  private static final long               STATS_LOG_INTERVAL_MS = 1000;
  private static final int                PIPELINE_SIZE = 24; //  Default set to give reasonable results on 2 and 4 cores

  private static final boolean            ADJUST_EXPLORATION_BIAS_FROM_TREE_SHAPE = false;

  /**
   * Once the plan is shorter than this start building up the search tree (using normal move time limits).  Used for
   * testing only (in which case it is set to 2).
   */
  public static int                       thinkBelowPlanSize = 0;

  private volatile long                   moveTime;
  private volatile long                   startTime;
  private volatile int                    searchSeqRequested  = 0;
  private volatile int                    searchSeqProcessing = 0;
  private volatile boolean                requestYield        = false;
  private MCTSTree[]                      factorTrees;
  private GamePlan                        mPlan               = null;
  private final Pool<TreeNode>            mNodePool;
  private final Pool<TreeEdge>            mEdgePool;
  private final UncappedPool<TreePath>    mPathPool;
  private final Pool<RAVEStats>           mRAVEStatsPool;
  private final ScoreVectorPool           mScoreVectorPool;
  private RolloutProcessorPool            rolloutPool         = null;
  private double                          minExplorationBias  = 0.5;
  private double                          maxExplorationBias  = 1.2;
  private volatile boolean                mTerminateRequested = false;
  private RuntimeGameCharacteristics      mGameCharacteristics;
  private Pipeline                        mPipeline;
  private long                            mLastNumIterations  = 0;
  private long                            mNumIterations      = 0;
  private int                             mRootDepth          = 0;
  private boolean                         mSuppressSampleSizeUpdate = false;
  private int                             mMaxIterationsPerTurn = -1;
  private int                             mPreviousExpectedScore = -10000;  //  Initial to large negative value a sentinel
  /**
   * Whether this searcher uses RAVE
   */
  public boolean                          mUseRAVE = false;
  public boolean                          mUseGoalGreedy = false;
  /**
   * Trace of moves played below the current node being updated by back propagation
   * (including the playout itself).  Enabled only if RAVE is in use.  BitSet indexes
   * are onto the master move list
   */
  private OpenBitSet                      mPlayoutTrace = null;

  private final String                    mLogName;

  private final SampleAverageMean         mAverageFringeDepth = new SampleAverageMean();
  private final SampleAverageRMS          mRMSFringeDepth = new SampleAverageRMS();

  /**
   * The move chosen at the end of a turn.  (Cleared at the start of a new one, when the tree is re-rooted.)
   */
  private Move                            mChosenMove;

  /**
   * Average observed branching factor from ode expansions
   */
  public final SampleAverage              mAverageBranchingFactor = new SampleAverageMean();

  /**
   * The last combined performance statistics from all rollout threads.
   */
  private RolloutPerfStats                mLastRolloutPerfStats = new RolloutPerfStats(0, 0);

  /**
   * Longest observed latency for a rollout.  Used by the performance analysis test.
   */
  public long longestObservedLatency = 0;

  /**
   * Average latency.  Used by the performance analysis test.
   */
  public long averageLatency = 0;

  /**
   * Number of completed rollouts.  !! ARR Appears to be a duplicate of mNumIterations?
   */
  private long numCompletedRollouts = 0;

  private double currentExplorationBias;

  private Tlkio mBroadcaster = null;
  private long localSearchRefreshTime;
  private int rootDepthAtLastLocalSearchStart = 0;
  private MoveConsequenceSearcher     moveConsequenceSearcher         = null;
  private ForwardDeadReckonInternalMachineState localSearchRoot       = null;
  private ForwardDeadReckonLegalMoveInfo priorityLocalSearchSeed      = null;
  private LocalSearchResults searchResultsBuffer = new LocalSearchResults();
  private volatile int lastProcessedSearchResultSeq = 0;
  private int lastQueuedSearchResultSeq = 0;
  private int localSearchResultProcessingAttemptSeq = 0;
  private static final int LOCAL_SEARCH_WIN_PROCESSING_MAX_RETRIES = 100;
  /**
   * Accumulated iteration timings.
   */
  private long mSelectTime;
  private long mExpandTime;
  private long mGetSlotTime;
  private long mRolloutTime;
  private long mBackPropTime;

  /**
   * Tree statistics when the last log was made.  (Used for calculating rates.)
   */
  private int mLastAllocations = 0;
  private int mLastTranspositions = 0;

  /**
   * Buffer for state information that may be freely used by the main search thread
   */
  StateInfo mStateInfoBuffer;

  /**
   * Create a game tree searcher with the specified maximum number of nodes.
   *
   * @param nodeTableSize - the maximum number of nodes.
   * @param numRoles      - the number of roles in the game.
   * @param xiLogName     - the name of the log.
   */
  public GameSearcher(int nodeTableSize, int numRoles, String xiLogName)
  {
    // mNodePool = new UnsafePool<>(TreeNode.class, nodeTableSize);
    mNodePool = new CappedPool<>(nodeTableSize);
    mEdgePool = new UncappedPool<>(nodeTableSize * 2);
    mPathPool = new UncappedPool<>(PIPELINE_SIZE * 2);
    mRAVEStatsPool = new UncappedPool<>(nodeTableSize);
    mScoreVectorPool = new NativeScoreVectorPool(nodeTableSize, numRoles);
    mLogName = xiLogName;
  }

  /**
   * Set the Tlk.io broadcaster to use for this game
   * @param xiBroadcaster
   */
  public void setBroadcaster(Tlkio xiBroadcaster)
  {
    mBroadcaster = xiBroadcaster;
  }

  /**
   * @return The Tlk.io broadcaster to use for this game
   */
  public Tlkio getBroadcaster()
  {
    return mBroadcaster;
  }

  /**
   * Limit the exploration bias to the specified range.
   *
   * @param min - the minimum exploration bias.
   * @param max - the maximum exploration bias.
   */
  public void setExplorationBiasRange(double min, double max)
  {
    minExplorationBias = min;
    maxExplorationBias = max;
    currentExplorationBias = (min + max)/2;

    LOGGER.info("Set explorationBias range to [" + minExplorationBias + ", " + maxExplorationBias + "]");
  }

  /**
   * Configure the game searcher.  This method must be called before startSearch().
   *
   * @param underlyingStateMachine
   * @param initialState
   * @param roleOrdering
   * @param gameCharacteristics
   * @param disableGreedyRollouts
   * @param heuristic
   * @param plan - pre-prepared set of moves to use (or null for none).
   */
  public void setup(ForwardDeadReckonPropnetStateMachine underlyingStateMachine,
                    ForwardDeadReckonInternalMachineState initialState,
                    RoleOrdering roleOrdering,
                    RuntimeGameCharacteristics gameCharacteristics,
                    boolean disableGreedyRollouts,
                    Heuristic heuristic,
                    GamePlan plan,
                    ForwardDeadReckonPropositionInfo[] roleControlProps)
  {
    mGameCharacteristics = gameCharacteristics;
    mPlan = plan;

    mStateInfoBuffer = new StateInfo(underlyingStateMachine.getRoles().size());

    mMaxIterationsPerTurn = MachineSpecificConfiguration.getCfgInt(CfgItem.MAX_ITERATIONS_PER_TURN);

    if (ThreadControl.ROLLOUT_THREADS > 0)
    {
      mPipeline = new Pipeline(PIPELINE_SIZE, underlyingStateMachine.getRoles().size(), underlyingStateMachine);
    }

    rolloutPool = new RolloutProcessorPool(mPipeline, underlyingStateMachine, roleOrdering, mLogName);

    LOGGER.info((disableGreedyRollouts ? "Not using" : "Using") + " greedy rollouts");
    if (disableGreedyRollouts)
    {
      underlyingStateMachine.enableGreedyRollouts(false, true);
      rolloutPool.disableGreedyRollouts();
    }

    mNodePool.clear(new TreeNodeAllocator(null), false);

    Set<Factor> factors = underlyingStateMachine.getFactors();
    if (factors == null)
    {
      factorTrees = new MCTSTree[] {new MCTSTree(underlyingStateMachine,
                                                 null,
                                                 mNodePool,
                                                 mScoreVectorPool,
                                                 mEdgePool,
                                                 mPathPool,
                                                 mRAVEStatsPool,
                                                 roleOrdering,
                                                 rolloutPool,
                                                 gameCharacteristics,
                                                 heuristic,
                                                 this,
                                                 roleControlProps)};
    }
    else
    {
      factorTrees = new MCTSTree[factors.size()];
      int lii = 0;
      for (Factor factor : factors)
      {
        factorTrees[lii++] = new MCTSTree(underlyingStateMachine,
                                          factor,
                                          mNodePool,
                                          mScoreVectorPool,
                                          mEdgePool,
                                          mPathPool,
                                          mRAVEStatsPool,
                                          roleOrdering,
                                          rolloutPool,
                                          gameCharacteristics,
                                          heuristic.createIndependentInstance(),
                                          this,
                                          roleControlProps);

      }
    }

    mNodePool.setNonFreeThreshold(factorTrees.length * MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR);

    if (MachineSpecificConfiguration.getCfgBool(CfgItem.USE_LOCAL_SEARCH))
    {
      if (factorTrees.length == 1 &&
          gameCharacteristics.numRoles == 2 &&
          !gameCharacteristics.isPseudoSimultaneousMove &&
          gameCharacteristics.isStrictlyAlternatingPlay &&
          gameCharacteristics.getMinNonDrawLength() < gameCharacteristics.getAverageLength()/2 &&
          gameCharacteristics.getAverageLength() < (2*(double)gameCharacteristics.getMaxLength())/3)
      {
        localSearchRoot = underlyingStateMachine.createEmptyInternalState();
        moveConsequenceSearcher = new MoveConsequenceSearcher(underlyingStateMachine.createInstance(), roleOrdering, mLogName, this);
      }
      else
      {
        LOGGER.info("This game is not suitable for local search");
      }
    }

    for (MCTSTree tree : factorTrees)
    {
      tree.mRoot = tree.allocateNode(initialState, null, false);
      tree.mRoot.mDecidingRoleIndex = gameCharacteristics.numRoles - 1;
      tree.mRoot.setDepth((short)0);
    }

    if (mUseRAVE)
    {
      mPlayoutTrace = new OpenBitSet(underlyingStateMachine.getFullPropNet().getMasterMoveList().length);

      LOGGER.info("Using RAVE");
    }
    else
    {
      mPlayoutTrace = null;

      LOGGER.info("Not using RAVE");
    }
  }


  @Override
  public void run()
  {
    // Register this thread.
    ThreadContext.put("matchID", mLogName);
    ThreadControl.registerSearchThread();

    long lNextUpdateSampleSizeTime = 0;
    long lNextStatsTime = System.currentTimeMillis() + STATS_LOG_INTERVAL_MS;

    try
    {
      while (searchAvailable() && (!mTerminateRequested))
      {
        try
        {
          LOGGER.info("Move search started");

          if (lNextUpdateSampleSizeTime == 0)
          {
            LOGGER.info("Starting sample size update timer");
            lNextUpdateSampleSizeTime = System.currentTimeMillis() + SAMPLE_SIZE_UPDATE_INTERVAL_MS;
          }

          boolean complete = false;
          while (!complete && !mTerminateRequested)
          {
            long time = System.currentTimeMillis();

            // Every STATS_LOG_INTERVAL_MS make a whole load of stats logs.
            if (time > lNextStatsTime)
            {
              logStats(time);
              lNextStatsTime += STATS_LOG_INTERVAL_MS;
            }

            // Every SAMPLE_SIZE_UPDATE_INTERVAL_MS, recalculate how many rollouts to do per iteration.
            if ((USE_DYNAMIC_SAMPLE_SIZING) && (time > lNextUpdateSampleSizeTime))
            {
              updateSampleSize();
              lNextUpdateSampleSizeTime += SAMPLE_SIZE_UPDATE_INTERVAL_MS;
            }

            if (requestYield)
            {
              Thread.yield();

              // Because this thread has been yielding, it hasn't been filling the rollout pipeline.  Therefore, the
              // performance stats from the rollout threads are meaningless and mustn't be used to update the sample
              // size.
              mSuppressSampleSizeUpdate = true;
            }
            else
            {
              synchronized(getSerializationObject())
              {
                assert(ThreadControl.takeTreeOwnership());

                //  Must re-test for a termination request having obtained the lock
                if (!mTerminateRequested)
                {
                  for (MCTSTree tree : factorTrees)
                  {
                    tree.mGameCharacteristics.setExplorationBias(currentExplorationBias);
                  }

                  // Grow the search tree - this is the heart of the GameSearcher function.
                  complete = expandSearch(false);
                }

                assert(ThreadControl.releaseTreeOwnership());
              }
            }
          }

          LOGGER.info("Move search complete: mTerminateRequested = " + mTerminateRequested +
                      ", complete = " + complete);

          // Because this thread has finished searching the game tree, it will no longer fill the rollout pipeline.
          // Therefore, the performance stats from the rollout threads are meaningless and mustn't be used to update the
          // sample size.
          mSuppressSampleSizeUpdate = true;
        }
        catch (AssertionError lEx)
        {
          // Rethrow assertions so that we quit the loop.
          LOGGER.error("AssertionError", lEx);
          assert(ThreadControl.abortTreeOwnership());
          throw new AssertionError("Rethrown AssertionError", lEx);
        }
        catch (Exception lEx)
        {
          // Log other exceptions, but attempt to carry on.
          LOGGER.error("Exception", lEx);
          assert(ThreadControl.abortTreeOwnership());
        }
      }
    }
    catch (InterruptedException lEx)
    {
      LOGGER.warn("Game search unexpectedly interrupted", lEx);
    }

    LOGGER.info("Terminating GameSearcher (mTerminateRequested = " + mTerminateRequested + ")");
  }

  /**
   * @return whether the search is complete.  The search is complete if it's complete in all factors.
   */
  public boolean isComplete()
  {
    if (mMaxIterationsPerTurn != -1 && getNumIterations() >= mMaxIterationsPerTurn)
    {
      return true;
    }
    if (mPlan.size() > GameSearcher.thinkBelowPlanSize)
    {
      return true;
    }
    for (MCTSTree tree : factorTrees)
    {
      if (!tree.mRoot.mComplete)
      {
        return false;
      }
    }

    return true;
  }

  private void logStats(long xiTime)
  {
    StringBuffer lLogBuf = new StringBuffer(1024);
    Series.NODE_EXPANSIONS.logDataPoint(lLogBuf, xiTime, mNumIterations);
    Series.POOL_USAGE.logDataPoint(lLogBuf, xiTime, mNodePool.getPoolUsage());

    int numReExpansions = 0;
    int newNumAllocations = 0;
    int newNumTranspositions = 0;

    for (MCTSTree factorTree : factorTrees)
    {
      numReExpansions += factorTree.mNumReExpansions;
      newNumAllocations += factorTree.getNumAllocations();
      newNumTranspositions += factorTree.getNumTranspositions();
    }

    Series.NODE_RE_EXPANSIONS.logDataPoint(lLogBuf, xiTime, numReExpansions);
    if (newNumAllocations > mLastAllocations)
    {
      Series.TRANSITION_RATE.logDataPoint(lLogBuf, xiTime, (100*(newNumTranspositions-mLastTranspositions))/(newNumAllocations-mLastAllocations));
    }
    mLastAllocations = newNumAllocations;
    mLastTranspositions = newNumTranspositions;

    // Log the iteration timings
    long lTotalTime = mSelectTime +
                      mExpandTime +
                      mGetSlotTime +
                      mRolloutTime +
                      mBackPropTime;
    if (lTotalTime != 0)
    {
      long lRunning = mSelectTime;
      Series.STACKED_SELECT.logDataPoint(lLogBuf, xiTime, lRunning * 100 / lTotalTime);

      lRunning += mExpandTime;
      Series.STACKED_EXPAND.logDataPoint(lLogBuf, xiTime, lRunning * 100 / lTotalTime);

      lRunning += mGetSlotTime;
      Series.STACKED_GET_SLOT.logDataPoint(lLogBuf, xiTime, lRunning * 100 / lTotalTime);

      lRunning += mRolloutTime;
      Series.STACKED_ROLLOUT.logDataPoint(lLogBuf, xiTime, lRunning * 100 / lTotalTime);

      lRunning += mBackPropTime;
      Series.STACKED_BACKPROP.logDataPoint(lLogBuf, xiTime, lRunning * 100 / lTotalTime);

      assert(lRunning == lTotalTime) : "Timings don't add up - " + lRunning + " != " + lTotalTime;
    }

    mSelectTime   = 0;
    mExpandTime   = 0;
    mGetSlotTime  = 0;
    mRolloutTime  = 0;
    mBackPropTime = 0;

    //Future intent will be to add these to the stats logger when it is stable
    //double fringeDepth = mAverageFringeDepth.getMean();
    //Series.FRINGE_DEPTH.logDataPoint(lLogBuf, time, (long)(fringeDepth+0.5));
    //Series.TREE_ASPECT_RATIO.logDataPoint(lLogBuf, time, (long)(nodePool.getNumUsedItems()/(fringeDepth*fringeDepth)));
    //mAverageFringeDepth.clear();

    STATS_LOGGER.info(lLogBuf.toString());

    if (ADJUST_EXPLORATION_BIAS_FROM_TREE_SHAPE)
    {
      double fringeDepth = mAverageFringeDepth.getAverage();
      double branchingFactor = mAverageBranchingFactor.getAverage();

      //  Adjust the branching factor to the correct geometric mean
      if (!mGameCharacteristics.isSimultaneousMove)
      {
        branchingFactor = Math.exp(Math.log(branchingFactor*mGameCharacteristics.numRoles)/mGameCharacteristics.numRoles);
      }
      if (fringeDepth > 0 && branchingFactor > 0)
      {
        //  Calculate the tree aspect ratio, which is the ratio of the observed average fringe
        //  depth to its expected depth given its observed branching factor
        double aspect = fringeDepth/(Math.log(mNodePool.getNumItemsInUse())/Math.log(branchingFactor));

        if (aspect < 1.2 && currentExplorationBias > 0.2)//minExplorationBias)
        {
          currentExplorationBias /= 1.1;
          LOGGER.info("Decreasing exploration bias to: " + currentExplorationBias);
        }
        else if (aspect > 1.3 && currentExplorationBias < maxExplorationBias)
        {
          currentExplorationBias *= 1.1;
          LOGGER.info("Increasing exploration bias to: " + currentExplorationBias);
        }
      }
    }
    else
    {
      double percentThroughTurn = Math.min(100, (xiTime - startTime) * 100 / (moveTime - startTime));
      currentExplorationBias = maxExplorationBias -
                                percentThroughTurn *
                                (maxExplorationBias - minExplorationBias) /
                                100;
    }
  }

  /**
   * @param resultingState Set to the state resulting from the first move in the primary path which was a choice
   * @return First move in primary path where there was a choice
   */
  public TreeEdge getPrimaryPath(ForwardDeadReckonInternalMachineState resultingState)
  {
    //  Currently no attempt to local serach in factored games
    if (factorTrees.length > 1)
    {
      return null;
    }

    synchronized(getSerializationObject())
    {
      FactorMoveChoiceInfo factorChoice = factorTrees[0].getBestMove(true);

      if (factorChoice.mBestEdge == null)
      {
        return null;
      }

      resultingState.copy(factorChoice.mResultingState);
      return factorChoice.mBestEdge;
    }
  }

  /**
   * @return the best move discovered (from the current root of the tree).
   */
  public Move getBestMove()
  {
    synchronized(getSerializationObject())
    {
      Move result = null;

      //  If we instigated a plan during this move calculation we must play from it
      if (!mPlan.isEmpty())
      {
        result = mPlan.nextMove();
        LOGGER.info("Playing first move from new plan: " + result);

        if (mPlan.size() > thinkBelowPlanSize)
        {
          //  No point in further searching
          terminate();
        }
      }

      if (result == null)
      {
        FactorMoveChoiceInfo bestChoice = null;

        //  The following will move to (or also reflect in) the stats logger once it is stable
        double fringeDepth = mAverageFringeDepth.getAverage();
        double RMSFringeDepth = mRMSFringeDepth.getAverage();
        double branchingFactor = mAverageBranchingFactor.getAverage();
        if (fringeDepth > 0 && branchingFactor > 0)
        {
          //  Adjust the branching factor to the correct geometric mean
          if (!mGameCharacteristics.isSimultaneousMove)
          {
            branchingFactor = Math.exp(Math.log(branchingFactor*mGameCharacteristics.numRoles)/mGameCharacteristics.numRoles);
          }
          LOGGER.info("Average fringe depth: " + fringeDepth);
          LOGGER.info("Fringe depth variability: " + (RMSFringeDepth - fringeDepth)/fringeDepth);
          LOGGER.info("Average branching factor: " + branchingFactor);
          //  Calculate the tree aspect ratio, which is the ratio of the observed average fringe
          //  depth to its expected depth given its observed branching factor
          double aspect = fringeDepth/(Math.log(mNodePool.getNumItemsInUse())/Math.log(branchingFactor));
          LOGGER.info("Tree aspect ratio: " + aspect);
        }

        LOGGER.debug("Searching for best move amongst factors:");
        for (MCTSTree tree : factorTrees)
        {
          FactorMoveChoiceInfo factorChoice = tree.getBestMove(false);
          if (factorChoice.mBestMove != null)
          {
            factorChoice.logFull();

            if (bestChoice == null)
            {
              bestChoice = factorChoice;
            }
            else
            {
              if (factorChoice.mPseudoNoopValue <= 0 && factorChoice.mPseudoMoveIsComplete &&
                  factorChoice.mBestMoveValue > 0 &&
                  (!bestChoice.mPseudoMoveIsComplete || bestChoice.mPseudoNoopValue > 0))
              {
                //  If no-oping this factor is a certain loss but the same is not true of the other
                //  factor then take this factor
                LOGGER.debug("  Factor move avoids a loss so selecting");
                bestChoice = factorChoice;
              }
              else if (bestChoice.mPseudoNoopValue <= 0 && bestChoice.mPseudoMoveIsComplete &&
                  bestChoice.mBestMoveValue > 0 &&
                  (!factorChoice.mPseudoMoveIsComplete || factorChoice.mPseudoNoopValue > 0))
              {
                //  If no-oping the other factor is a certain loss but the same is not true of this
                //  factor then take the other factor
                LOGGER.debug("  Factor move would be loss in other factor");
              }
              // Complete win dominates everything else
              else if (factorChoice.mBestMoveValue > 100-TreeNode.EPSILON && factorChoice.mBestMoveIsComplete)
              {
                LOGGER.debug("  Factor move is a win so selecting");
                bestChoice = factorChoice;
              }
              else if ((bestChoice.mBestMoveValue > 100-TreeNode.EPSILON && bestChoice.mBestMoveIsComplete) ||
                       (factorChoice.mBestMoveValue <= TreeNode.EPSILON && factorChoice.mBestMoveIsComplete))
              {
                LOGGER.debug("  Already selected factor move is a win or this move is a loss - not selecting");
                continue;
              }
              // otherwise choose the one that reduces the resulting net chances the least weighted
              // by the resulting win chance in the chosen factor.  This biases the player towards
              // concentrating on the factor it is most ahead in, and is somewhat experimental (since ignoring
              // the factor you are behind in could be rather bad too!)
              else
              {
                if (bestChoice.mBestMoveValue*(bestChoice.mBestMoveValue - bestChoice.mPseudoNoopValue) <
                    factorChoice.mBestMoveValue*(factorChoice.mBestMoveValue - factorChoice.mPseudoNoopValue))
                {
                  bestChoice = factorChoice;
                  LOGGER.debug("  This factor score is superior - selecting");
                }
                else
                {
                  LOGGER.debug("  This factor score is inferior - not selecting");
                }
              }
            }
          }
          else
          {
            LOGGER.warn("  Factor best move is NULL");
          }
        }

        assert(bestChoice != null) : "No move choice found";

        double expectedScore = Math.max(0, bestChoice.mBestMoveValue + 0.5);
        StatsLogUtils.Series.SCORE.logDataPoint((long)expectedScore);
        result = (bestChoice.mBestMove.mIsPseudoNoOp ? null : bestChoice.mBestMove.mMove);

        makePerTurnScoreAnnouncement((int)expectedScore, bestChoice);
      }

      // Record that we've made the move.  Until we've heard back from the server, the game searcher will always search
      // down this branch.  (This must be done before we release the game searcher again.)
      // Note - we ASSUME that the player will now play the move we told it to - we need to record the chosen move
      // within the synchronized section or else a plan that is found between selecting the best move here, and recording
      // that we played it will not know whether to include the root move or not
      chooseMove(result);

      return result;
    }
  }

  private void makePerTurnScoreAnnouncement(int xiExpectedScore, FactorMoveChoiceInfo xiBestChoice)
  {
    StringBuilder lAnnouncement = new StringBuilder(1024);
    int lTurnDifference = (xiExpectedScore - mPreviousExpectedScore);

    if (lTurnDifference < 1000  && Math.abs(lTurnDifference) > 10)
    {
      if (lTurnDifference > 0)
      {
        lAnnouncement.append("Things seem to be looking up - ");
      }
      else
      {
        lAnnouncement.append("Uh oh, I don't like the way this is going - ");
      }
    }

    if (mPreviousExpectedScore * xiExpectedScore < 0 ||
        mPreviousExpectedScore / 10 != xiExpectedScore / 10)
    {
      if (xiExpectedScore < 0)
      {
        lAnnouncement.append("I appear to be about to lose :-(");
      }
      else if (xiExpectedScore < 30)
      {
        lAnnouncement.append("This looks very bad!");
      }
      else if (xiExpectedScore < 40)
      {
        lAnnouncement.append("This is not looking so good for me");
      }
      else if (xiExpectedScore < 60)
      {
        lAnnouncement.append("Not sure which way this game is going yet");
      }
      else if (xiExpectedScore < 70)
      {
        lAnnouncement.append("This position looks pretty decent");
      }
      else if (xiExpectedScore < 100 - TreeNode.EPSILON)
      {
        lAnnouncement.append("This is looking like I should win now");
      }
      else
      {
        lAnnouncement.append("I think I've got this in the bag");
      }

      lAnnouncement.append(" (");
      lAnnouncement.append(xiExpectedScore);
      lAnnouncement.append("%)");
    }
    else
    {
      lAnnouncement.append("My current assessment is ");
      lAnnouncement.append(xiExpectedScore);
      lAnnouncement.append("%.");

      if (xiBestChoice.mPathTrace != null)
      {
        lAnnouncement.append("  ");
        lAnnouncement.append(xiBestChoice.mPathTrace);
      }
    }

    if (mBroadcaster != null)
    {
      mBroadcaster.broadcast(lAnnouncement.toString());
    }

    mPreviousExpectedScore = xiExpectedScore;
  }

  /**
   * Expand the search tree by performing a single MCTS iteration across all factor trees.
   *
   * @param forceSynchronous - true if the rollout should be performed synchronously (by this thread).
   *
   * @return whether all factor trees have been completely explored.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   */
  public boolean expandSearch(boolean forceSynchronous)
    throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    boolean lAllTreesCompletelyExplored;

    if (mMaxIterationsPerTurn != -1 && getNumIterations() >= mMaxIterationsPerTurn)
    {
      return true;
    }

    while (mNodePool.isFull())
    {
      boolean somethingDisposed = false;

      if (DISABLE_NODE_TRIMMING)
      {
        return false;
      }

      for (MCTSTree tree : factorTrees)
      {
        if (!tree.mRoot.mComplete)
        {
          //  The trees may have very asymmetric sizes due to one being nearly
          //  complete, in which case it is possible that no candidates for trimming
          //  will be found.  This should not be possible in all trees if the node pool
          //  is nearly full, so check that at least one tree does release something
          somethingDisposed |= tree.mRoot.disposeLeastLikelyNode();
        }
      }

      if (!somethingDisposed)
      {
        //  Sometimes hyper-edges can block trimming.  For now in such cases we just accept that we cannot search any
        //  more until the next turn frees up some space
        //  TODO - find a way to trim even in these cases, or at least to continue to select and perform playouts/updates
        //  without doing further expansions
        assert(factorTrees[0].mRoleControlProps != null && factorTrees[0].mRemoveNonDecisionNodes);

        LOGGER.warn("Cannot trim due to hyper-edges");
        return false;
      }
    }

    if (moveConsequenceSearcher != null && moveConsequenceSearcher.isEnabled())
    {
      ProcessQueuedLocalSearchResults();

      if (System.currentTimeMillis() > localSearchRefreshTime)
      {
        ForwardDeadReckonLegalMoveInfo primaryLine = null;
        int choosingRole = 0;

        if (priorityLocalSearchSeed != null && getRootDepth() != rootDepthAtLastLocalSearchStart)
        {
          LOGGER.info("Setting priority search seed: " + priorityLocalSearchSeed);
          primaryLine = priorityLocalSearchSeed;
          priorityLocalSearchSeed = null;
          choosingRole = (getRootDepth()/2)%2;
        }
        else
        {
          TreeEdge primaryPathEdge = getPrimaryPath(localSearchRoot);
          if (primaryPathEdge != null)
          {
            TreeNode primaryPathNode = factorTrees[0].mRoot.get(primaryPathEdge.getChildRef());

            primaryLine = primaryPathEdge.mPartialMove;
            if (primaryPathNode.mLocalSearchStatus == LocalSearchStatus.LOCAL_SEARCH_UNSEARCHED)
            {
              primaryPathNode.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_NO_RESULT;
            }
            choosingRole = (primaryPathNode.mDecidingRoleIndex+1)%2;
          }
        }

        long timeRemaining = moveTime - System.currentTimeMillis();

        if (primaryLine != null)
        {
          moveConsequenceSearcher.newSearch(localSearchRoot, factorTrees[0].mRoot.mState, primaryLine, choosingRole, getRootDepth() != rootDepthAtLastLocalSearchStart, false);
          rootDepthAtLastLocalSearchStart = getRootDepth();

          //  Recheck periodically that we're still thinking the same move is most interesting.
          //  We don't want to wind up chopping backwards and forwards between a couple of moves
          //  and having to restart frequently, so give a fixed proportion the remaining time at each rechoice
          //  (down to a reasonable minimum)
          localSearchRefreshTime = System.currentTimeMillis() + Math.max(timeRemaining/3, MIN_LOCAL_SEARCH_REFRESH_PERIOD);
        }
        else
        {
          //  Recheck after a short period since currently the root has no primary line, which means either
          //  it's complete (in which case search is about to stop anyway) or it has not yet been expanded after
          //  a fresh re-rooting of the tree
          localSearchRefreshTime = System.currentTimeMillis() + Math.max(timeRemaining/3, 50);
        }
      }
    }

    lAllTreesCompletelyExplored = true;
    for (MCTSTree tree : factorTrees)
    {
      if (!tree.mRoot.mComplete)
      {
        if (ThreadControl.ROLLOUT_THREADS > 0 && !forceSynchronous)
        {
          // If there's back-propagation work to do, do it now, in preference to more select/expand cycles because the
          // back-propagation will mean that subsequent select/expand cycles are more accurate.
          processCompletedRollouts(false);
        }

        // Perform an MCTS iteration.
        lAllTreesCompletelyExplored &= tree.growTree(forceSynchronous, mChosenMove);
      }
    }

    return lAllTreesCompletelyExplored;
  }

  /**
   * @return the number of iterations performed.
   */
  public int getNumIterations()
  {
    synchronized(this)
    {
      return (int)(mNumIterations - mLastNumIterations);
    }
  }

  /**
   * @return the number of rollouts performed.  (This can be more than the number of iterations if the sample size is,
   * or has been, greater than 1.)
   */
  public int getNumRollouts()
  {
    int result = 0;

    for (MCTSTree tree : factorTrees)
    {
      result += tree.mNumNonTerminalRollouts;
    }

    return result;
  }

  private boolean searchAvailable() throws InterruptedException
  {
    synchronized (this)
    {
      if (searchSeqRequested == searchSeqProcessing)
      {
        this.notifyAll();
        if (!mTerminateRequested)
        {
          this.wait();
        }
      }

      searchSeqProcessing = searchSeqRequested;
    }

    return true;
  }

  /**
   * @return the pipeline being used by this game searcher.
   */
  public Pipeline getPipeline()
  {
    return mPipeline;
  }

  /**
   * Start searching the game tree (or re-root the tree at the start of a new turn).
   *
   * @param moveTimeout - the time (in milliseconds) at which a move must be submitted.
   * @param startState  - the state at the root of the tree.
   * @param rootDepth   - the current depth (in turns since the beginning of the game) of the tree root.
   */
  public void startSearch(long moveTimeout,
                          ForwardDeadReckonInternalMachineState startState,
                          short rootDepth,
                          ForwardDeadReckonLegalMoveInfo lastMove)
  {
    // We no longer have a chosen move.
    mChosenMove = null;

    // If we don't have any factor trees, we're playing from a real live plan (not a test one) so there's nothing to do.
    if (factorTrees == null)
    {
      LOGGER.debug("No need to search when we have a full plan");
      return;
    }

    if (moveConsequenceSearcher != null && moveConsequenceSearcher.isEnabled())
    {
      int choosingRole  = (getRootDepth()/2)%2;

      if (lastMove != null)
      {
        moveConsequenceSearcher.newSearch(startState, null, lastMove, choosingRole, true, false);
      }

      rootDepthAtLastLocalSearchStart = rootDepth;
    }

    // Print out some statistics from last turn.
    LOGGER.info("MCTS iterations last turn = " + (mNumIterations - mLastNumIterations));
    mLastNumIterations = mNumIterations;

    LOGGER.debug("Start move search...");
    synchronized (this)
    {
      assert(ThreadControl.takeTreeOwnership());

      //  Devote the first few seconds to searching the last move played in case it wasn't what
      //  was previously expected and so had not been subject to local search
      localSearchRefreshTime = System.currentTimeMillis() + LOCAL_SEARCH_REVIEW_PLAYED_MOVE_TIME;

      for (MCTSTree tree : factorTrees)
      {
        tree.setRootState(startState, rootDepth, lastMove);
      }

      moveTime = moveTimeout;
      startTime = System.currentTimeMillis();
      searchSeqRequested++;
      setRootDepth(rootDepth);

      //  Clear stat averages
      mAverageFringeDepth.clear();
      mRMSFringeDepth.clear();
      mAverageBranchingFactor.clear();

      assert(ThreadControl.releaseTreeOwnership());
      this.notify();
    }
  }

  /**
   * Mark a move as one that has been chosen (by us).  The tree can't be re-rooted yet because we haven't heard the
   * complete set of moves back from the server - but we can assume that we'll have picked this move and therefore
   * only look down this branch.
   *
   * This is cleared when {@link #startSearch} is called (to re-root the tree).
   *
   * @param xiMove - the chosen move.
   */
  public void chooseMove(Move xiMove)
  {
    mChosenMove = xiMove;
  }

  @Override
  public void requestYield(boolean state)
  {
    // !! ARR This is a bit coarse.  We could pass more useful messages about the desired state (including whether we
    // !! ARR should be spinning through outstanding work from a previous turn, whether this is an emergency stop
    // !! ARR request, etc.)
    requestYield = state;
  }

  @Override
  public Object getSerializationObject()
  {
    // !! ARR Who locks against us (Sancho thread) and why (to start a new turn and all that entails + termination)?
    // !! ARR But can we do better and not have any other threads needing to access the tree / state machine / etc.?
    return this;
  }

  /**
   * Process one completed rollout request
   * @param lRequest
   * @return back-prop time
   */
  public long processCompletedRollout(RolloutRequest lRequest)
  {
    long lBackPropTime = 0;

    if (!lRequest.mPath.isFreed())
    {
      TreeNode lNode = TreeNode.get(mNodePool, lRequest.mNodeRef);
      if (lNode != null && !lNode.mComplete)
      {
        // Update min/max scores.
        if (lRequest.mMaxScore > lNode.mTree.mHighestRolloutScoreSeen)
        {
          lNode.mTree.mHighestRolloutScoreSeen = lRequest.mMaxScore;
        }

        if (lRequest.mMinScore < lNode.mTree.mLowestRolloutScoreSeen)
        {
          lNode.mTree.mLowestRolloutScoreSeen = lRequest.mMinScore;
        }

        int thisCompletionDepth = lRequest.mPlayoutInfo.playoutLength*lNode.mTree.mNumRoles + lNode.getDepth();
        if (thisCompletionDepth < lNode.mTree.mShallowestCompletionDepth)
        {
          lNode.mTree.mShallowestCompletionDepth = thisCompletionDepth;
          LOGGER.info("New minimum game length observed: " + thisCompletionDepth);
        }

        mAverageFringeDepth.addSample(lNode.getDepth() - getRootDepth());
        mRMSFringeDepth.addSample(lNode.getDepth() - getRootDepth());
        lRequest.mPath.resetCursor();

        if (lRequest.mIsWin && mGameCharacteristics.isPseudoPuzzle)
        {
          //  First build up the move path to the node that was rolled out from
          List<ForwardDeadReckonLegalMoveInfo> fullPlayoutList = new LinkedList<>();

          while(lRequest.mPath.hasMore())
          {
            lRequest.mPath.getNextNode();
            assert(lRequest.mPath.getCurrentElement() != null);

            TreeEdge edge = lRequest.mPath.getCurrentElement().getEdgeUnsafe();

            if (lRequest.mPath.getCurrentElement().getParentNode().mDecidingRoleIndex == lRequest.mTree.mNumRoles-1)
            {
              //  If we've already played a move (but not yet had the server confirm back to
              //  us) then we need to NOT include it in the plan
              if (mChosenMove != null && fullPlayoutList.isEmpty())
              {
                assert(edge.mPartialMove.mMove == mChosenMove);
              }
              else
              {
                fullPlayoutList.add(0, edge.mPartialMove);
              }
            }
          }

          lRequest.mPath.resetCursor();

          //  Now append the rollout path
          for(int moveIndex = 0; moveIndex < lRequest.mPlayoutInfo.playoutLength; moveIndex++)
          {
            fullPlayoutList.add(lRequest.mPlayoutInfo.playoutTrace[moveIndex]);
          }

          if (mBroadcaster != null && mPlan.isEmpty())
          {
            mBroadcaster.broadcast("Ah hah, I see a solution!");
          }

          //  Provide this winning path for consideration as our new plan
          mPlan.considerPlan(fullPlayoutList);
        }

        if (lRequest.mComplete)
        {
          //  Propagate the implications of the completion discovered by the playout
          lNode.markComplete(lRequest.mAverageScores, (short)(lNode.getDepth()+1));
          lNode.mTree.processNodeCompletions();
          lRequest.mPath.trimToCompleteLeaf();
          //  Trim down the update path so that we start updating only from the
          //  first completed node as several trailing elements may be complete
          lNode = lRequest.mPath.getTailElement().getChildNode();
        }

        if (mUseRAVE)
        {
          //  Decant played moves into a bitset for O(1) existance queries
          mPlayoutTrace.clear(0, mPlayoutTrace.capacity());

          assert(lRequest.mRecordPlayoutTrace);
          for(int i = 0; i < lRequest.mPlayoutInfo.playoutLength; i++)
          {
            mPlayoutTrace.set(lRequest.mPlayoutInfo.playoutTrace[i].mMasterIndex);
          }
        }

        lBackPropTime = lNode.updateStats(lRequest.mAverageScores,
                                          lRequest.mAverageSquaredScores,
                                          lRequest.mPath,
                                          lRequest.mWeight,
                                          mUseRAVE ? mPlayoutTrace : null);
      }
    }

    return lBackPropTime;
  }

  /**
   * Process all completed rollouts in the pipeline.
   *
   * @param xiNeedToDoOne - whether this method should block until at least 1 rollout has been performed.
   *
   * @return the amount of time (in nanoseconds) that this method stalled waiting for work to do (when xiNeedToDoOne
   *         is true).  This doesn't include time spent doing back-propagation of any items removed from the pipeline.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   */
  long processCompletedRollouts(boolean xiNeedToDoOne) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    assert(ThreadControl.checkTreeOwnership());

    long lStallTime = 0;
    boolean canBackPropagate;

    while ((canBackPropagate = mPipeline.canBackPropagate()) || xiNeedToDoOne)
    {
      if (xiNeedToDoOne && !canBackPropagate && mGameCharacteristics.getRolloutSampleSize() == 1)
      {
        // If the rollout threads are not keeping up and the pipeline is full then perform an expansion synchronously
        // while we wait for the rollout pool to have results for us.
        expandSearch(true);
        //mNumIterations++;

        // This method can be called re-entrantly from the above, which means that the rollout pipeline may no longer be
        // blocked, in which case we should return immediately.
        if (mPipeline.canExpand())
        {
          return 0;
        }
        continue;
      }

      long lStallStartTime = System.nanoTime();
      RolloutRequest lRequest = mPipeline.getNextRequestForBackPropagation();
      long lDequeue2Time = System.nanoTime();
      lStallTime = lDequeue2Time - lStallStartTime;

      if (longestObservedLatency < lRequest.mQueueLatency)
      {
        longestObservedLatency = lRequest.mQueueLatency;
      }
      averageLatency = (averageLatency*numCompletedRollouts + lRequest.mQueueLatency)/(numCompletedRollouts+1);
      numCompletedRollouts++;

      long lBackPropTime = processCompletedRollout(lRequest);

      recordIterationTimings(lRequest.mSelectElapsedTime,
                             lRequest.mExpandElapsedTime,
                             lRequest.mGetSlotElapsedTime,
                             lRequest.mEnqueue2Time - lRequest.mRolloutStartTime,
                             lBackPropTime);

      mPathPool.free(lRequest.mPath, 0);
      lRequest.mPath = null;
      mPipeline.completedBackPropagation();
      xiNeedToDoOne = false;
    }

    return lStallTime;
  }

  /**
   * Accumulate the timings from an iteration.
   *
   * @param xiSelectTime
   * @param xiExpandTime
   * @param xiGetSlotTime
   * @param xiRolloutTime
   * @param xiBackPropTime
   */
  void recordIterationTimings(long xiSelectTime,
                              long xiExpandTime,
                              long xiGetSlotTime,
                              long xiRolloutTime,
                              long xiBackPropTime)
  {
    mSelectTime   += xiSelectTime;
    mExpandTime   += xiExpandTime;
    mGetSlotTime  += xiGetSlotTime;
    mRolloutTime  += xiRolloutTime;
    mBackPropTime += xiBackPropTime;

    mNumIterations++;
  }

  /**
   * @return plan for the current game
   */
  public GamePlan getPlan()
  {
    return mPlan;
  }

  /**
   * Update the sample size in order to try to keep the search thread busy, because it is the only thread able to access
   * the tree (to avoid hideous locking code) and is therefore usually the bottleneck.
   *
   * Adjust the sample size to keep the rollout threads 80% busy.  This appears to give a happy trade-off between doing
   * as many samples as reasonably possible whilst not leaving the pipeline full (thereby blocking the search thread)
   * for prolonged periods.
   */
  private void updateSampleSize()
  {
    if (mPipeline != null)
    {
      // Get the most recent combined total statistics and calculate the difference from last time round.
      RolloutPerfStats lCombinedStatsTotal = new RolloutPerfStats(mPipeline.getRolloutPerfStats());
      RolloutPerfStats lStatsDiff = lCombinedStatsTotal.getDifference(mLastRolloutPerfStats);
      mLastRolloutPerfStats = lCombinedStatsTotal;

      double lSampleSize = mGameCharacteristics.getExactRolloutSampleSize();

      double lNewSampleSize = lSampleSize * 0.8 / lStatsDiff.mUsefulWorkFraction;

      // The measured ratio is really quite volatile.  To prevent the sample size jumping all over the place, adjust it
      // slowly.  The rules vary depending on whether the current sample size is very small (in which case we need to
      // take care to avoid rounding preventing any change).
      if (lSampleSize > 4)
      {
        // Only let the sample size 33% of the way towards its new value.  Also, only let it grow to 120% of its
        // previous value in one go.
        lNewSampleSize = (lNewSampleSize + (2 * lSampleSize)) / 3;
        lNewSampleSize = Math.min(1.2 * lSampleSize, lNewSampleSize);
      }
      else
      {
        // Very small sample size.  Jump straight to the new size, up to a maximum of 5.
        lNewSampleSize = Math.min(5.0,  lNewSampleSize);
      }

      // The sample size is always absolutely bound between 1 and 100 (inclusive).
      lNewSampleSize = Math.max(1.0,  Math.min(100.0, lNewSampleSize));

      //  If we are using RAVE we cannot have a sample size larger than 1
      if (mUseRAVE)
      {
        mGameCharacteristics.setRolloutSampleSize(1);
      }
      else
      {
        // Set the new sample size, unless it has to be suppressed because turn end processing meant we couldn't fill the
        // pipeline (and therefore have invalid measurements from the rollout threads).
        if (!mSuppressSampleSizeUpdate)
        {
          mGameCharacteristics.setRolloutSampleSize(lNewSampleSize);
        }
      }

      LOGGER.debug("Dynamic sample size");
      LOGGER.debug("  Useful work last time:  " + (int)(lStatsDiff.mUsefulWorkFraction * 100) + "%");
      LOGGER.debug("  Calculated sample size: " + (int)(lNewSampleSize + 0.5));
      LOGGER.debug("  Suppress update:        " + mSuppressSampleSizeUpdate);
      LOGGER.debug("  Now using sample size:  " + mGameCharacteristics.getRolloutSampleSize() + (mUseRAVE ? " (forced by use of RAVE)" : ""));
      LOGGER.debug("  Useful work total:      " + (int)(lCombinedStatsTotal.mUsefulWorkFraction * 100) + "%");

      mSuppressSampleSizeUpdate = false;
    }
  }

  /**
   * Terminate the game searcher and all child threads.
   */
  public void terminate()
  {
    synchronized(getSerializationObject())
    {
      if (rolloutPool != null)
      {
        rolloutPool.stop();
        rolloutPool = null;
      }

      if (moveConsequenceSearcher != null)
      {
        moveConsequenceSearcher.stop();
        moveConsequenceSearcher = null;
      }

      if (factorTrees != null)
      {
        for (MCTSTree lTree : factorTrees)
        {
          lTree.terminate();
        }
      }

      mScoreVectorPool.terminate();

      mTerminateRequested = true;
      notifyAll();
    }
  }

  /**
   * @return depth of root in game tree from initial state
   */
  public int getRootDepth()
  {
    return mRootDepth;
  }

  private void setRootDepth(int xiRootDepth)
  {
    mRootDepth = xiRootDepth;
  }

  @Override
  public void ProcessLocalSearchResult(LocalSearchResults xiResults)
  {
    LOGGER.info("Received search result for processing when numIterations=" + mNumIterations);
    while (lastProcessedSearchResultSeq != lastQueuedSearchResultSeq && !mTerminateRequested)
    {
      //  Spin-wait
      Thread.yield();
    }

    searchResultsBuffer.copyFrom(xiResults);
    lastQueuedSearchResultSeq++;
  }

  private void ProcessQueuedLocalSearchResults()
  {
    if (lastQueuedSearchResultSeq > lastProcessedSearchResultSeq)
    {
      LOGGER.info("Processing queued search results when numIterations=" + mNumIterations);

      //  Transfer the search results into the MCTS tree
      assert(factorTrees.length == 1);
      MCTSTree tree = factorTrees[0];

      assert(tree.mNumRoles == 2) : "Unexpected use of local search on non 2-player game";

      //  Find the root node for the local search
      TreeNode node = tree.findTransposition(searchResultsBuffer.startState);
      if (node == null)
      {
        LOGGER.warn("Unexpectedly unable to find MCTS node for root of completed local search");
      }
      else
      {
        if (node == tree.mRoot && node.mNumChildren == 1 && node.mChildren[0] instanceof TreeEdge)
        {
          //  Pseudo-root node - real first choice node is one down
          node = node.get(((TreeEdge)node.mChildren[0]).getChildRef());

          LOGGER.info("Node is pseudo-root, moving down to decision root");
        }
        if (node.mComplete)
        {
          LOGGER.info("Node already complete");
        }
        else
        {
          LOGGER.info("Node depth is " + node.getDepth() + " (root depth " + tree.mRoot.getDepth() + ")");

          boolean nodeIsRootEquivalent = (node.getDepth() <= tree.mRoot.getDepth()+1);
          boolean nodeIsRootChild = !nodeIsRootEquivalent && (node.getDepth() <= tree.mRoot.getDepth()+3);

          //  Is this a win, or a must-play-local-move?
          if (searchResultsBuffer.winForRole != -1)
          {
             assert(searchResultsBuffer.tenukiLossForRole == -1);

            //  If this completes the tree as a win for our opponent just ignore it!
            //  At this point it cannot help find an escape and letting MCTS in on the loss
            //  will just result in random moves, making the opponent's job that much easier.
            //  Better to let MCTS labour on in ignorance and try to make life as hard as
            //  possible for the opponent
            if (searchResultsBuffer.winForRole != 0 &&
                 (nodeIsRootEquivalent || (nodeIsRootChild && node.mDecidingRoleIndex == searchResultsBuffer.winForRole)))
            {
              LOGGER.info("Result is an unconditional loss for us - ignoring to allow MCTS to obfuscate!");
            }
            else
            {
              double completeResultBuffer[] = new double[tree.mNumRoles];

              for(int i = 0; i < tree.mNumRoles; i++)
              {
                completeResultBuffer[i] = (i == searchResultsBuffer.winForRole ? 100 : 0);
              }

              if (searchResultsBuffer.winForRole == node.mDecidingRoleIndex)
              {
                //  Unconditional win here whatever is played
                LOGGER.info("noop win for " + tree.mRoleOrdering.roleIndexToRole(searchResultsBuffer.winForRole) + " from seed move " + searchResultsBuffer.seedMove);
                node.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_WIN;
                node.mCompletionDepth = (short)(node.getDepth() + searchResultsBuffer.atDepth);
                assert(node.mCompletionDepth >= 0 && node.mCompletionDepth >= node.getDepth());

                if (node == tree.mRoot && searchResultsBuffer.winForRole == 0)
                {
                  LOGGER.info("Root is local win without known win path for us - storing seed move for next priority search seed");

                  priorityLocalSearchSeed = searchResultsBuffer.seedMove;
                }
              }
              else
              {
                boolean winIsValid = true;
                boolean moveFound = false;

                //  Win if the winning move is played
                for (short index = 0; index < node.mNumChildren; index++)
                {
                  Object choice = node.mChildren[index];

                  ForwardDeadReckonLegalMoveInfo moveInfo = ((choice instanceof TreeEdge) ? ((TreeEdge)choice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)choice);

                  if (moveInfo == searchResultsBuffer.winningMove)
                  {
                    moveFound = true;
                    if (node.mPrimaryChoiceMapping != null)
                    {
                      choice = node.mChildren[node.mPrimaryChoiceMapping[index]];
                    }
                    TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
                    if (edge != null && edge.getChildRef() != TreeNode.NULL_REF)
                    {
                      TreeNode child = node.get(edge.getChildRef());
                      if (child != null)
                      {
                        if (child.mComplete)
                        {
                          LOGGER.info("Move already complete with score for winning role of: " + child.getAverageScore(searchResultsBuffer.winForRole));
                        }
                        else if (child.mLocalSearchStatus == LocalSearchStatus.LOCAL_SEARCH_LOSS)
                        {
                          if (child.mCompletionDepth < (short)(node.getDepth()+searchResultsBuffer.atDepth-1))
                          {
                            LOGGER.info("Local win for " + tree.mRoleOrdering.roleIndexToRole(searchResultsBuffer.winForRole) + " from seed move " + searchResultsBuffer.seedMove + " with move " + searchResultsBuffer.winningMove + " ignored because it is a known local loss at lower depth");
                            winIsValid= false;
                          }
                          else
                          {
                            LOGGER.info("Win for " + tree.mRoleOrdering.roleIndexToRole(searchResultsBuffer.winForRole) + " from seed move " + searchResultsBuffer.seedMove + " with move " + searchResultsBuffer.winningMove + " overrides previously found deeper local loss");
                            child.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_WIN;
                            child.mCompletionDepth = (short)(node.getDepth()+searchResultsBuffer.atDepth-1);
                            assert(child.mCompletionDepth >= 0 && child.mCompletionDepth >= child.getDepth());
                          }
                        }
                        else
                        {
                          LOGGER.info("Win for " + tree.mRoleOrdering.roleIndexToRole(searchResultsBuffer.winForRole) + " from seed move " + searchResultsBuffer.seedMove + " with move " + searchResultsBuffer.winningMove);
                          child.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_WIN;
                          child.mCompletionDepth = (short)(node.getDepth()+searchResultsBuffer.atDepth-1);
                          assert(child.mCompletionDepth >= 0 && child.mCompletionDepth >= child.getDepth());
                        }
                        break;
                      }
                    }

                    LOGGER.info("Winning move " + searchResultsBuffer.winningMove + " from local search has no extant node in MCTS tree");

                    //  This can happen across a new move if the win was found while unreferenced parts of the old tree are being trimmed (which
                    //  can take a while) and the result is a root that need re-expanding.  In such cases this attempt to process the found
                    //  win can occur before that child is recreated in the first few dozen MCTS iterations following creation of the new
                    //  root.
                    //  To cope with this circumstance we just leave the result queued and allow up to a fixed threshold iterations to take
                    //  place before we give up (which should never really happen)
                    if (localSearchResultProcessingAttemptSeq > LOCAL_SEARCH_WIN_PROCESSING_MAX_RETRIES)
                    {
                      LOGGER.warn("Winning move " + searchResultsBuffer.winningMove + " from local search not found in MCTS tree after retry period");
                      break;
                    }

                    return;
                  }
                }

                //assert(moveFound) : "Unable to find winning move in tree";

                if (winIsValid)
                {
                  assert(node.mLocalSearchStatus != LocalSearchStatus.LOCAL_SEARCH_WIN);

                  node.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_LOSS;
                  node.mCompletionDepth = (short)(node.getDepth() + searchResultsBuffer.atDepth);
                  assert(node.mCompletionDepth >= 0 && node.mCompletionDepth >= node.getDepth());

                  if (searchResultsBuffer.choiceFromState != null && !searchResultsBuffer.seedMayEnableResult())
                  {
                    LOGGER.info("Win is not enabled by this seed, so checking for other non-relevant moves to eliminate");
                    TreeNode choiceFromNode = tree.findTransposition(searchResultsBuffer.choiceFromState);
                    if (choiceFromNode == null)
                    {
                      LOGGER.warn("Unexpectedly unable to find MCTS node for choice node");
                    }
                    else
                    {
                      for (short index = 0; index < choiceFromNode.mNumChildren; index++)
                      {
                        Object choice = choiceFromNode.mChildren[index];

                        if (choice instanceof TreeEdge)
                        {
                          TreeEdge edge = (TreeEdge)choice;
                          if (edge.getChildRef() != TreeNode.NULL_REF)
                          {
                            TreeNode childNode = node.get(edge.getChildRef());
                            if (childNode != null)
                            {
                              if (!searchResultsBuffer.canInfluenceFoundResult(edge.mPartialMove))
                              {
                                LOGGER.info("Looks like move " + edge.mPartialMove + " would also allow this win");
                                childNode.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_LOSS;
                                childNode.mCompletionDepth = (short)(childNode.getDepth() + searchResultsBuffer.atDepth);
                                assert(childNode.mCompletionDepth >= 0 && childNode.mCompletionDepth >= childNode.getDepth());
                              }
                              else
                              {
                                LOGGER.info("Choice " + edge.mPartialMove + " could influence the win");
                              }
                            }
                            else
                            {
                              LOGGER.info("Choice " + edge.mPartialMove + " freed");
                            }
                          }
                          else
                          {
                            LOGGER.info("Choice " + edge.mPartialMove + " unexpanded");
                          }
                        }
                        else
                        {
                          LOGGER.info("Choice " + choice + " unexpanded");
                        }
                      }
                    }
                  }
                }

                if (node == tree.mRoot && searchResultsBuffer.winForRole == 0)
                {
                  LOGGER.info("Root complete with known win path for us - storing winning move for next priority search seed");

                  priorityLocalSearchSeed = searchResultsBuffer.winningMove;
                }
              }
            }

            //  Re-evaluate what we should be searching in light of the tree changes
            localSearchRefreshTime = System.currentTimeMillis();
          }
          else
          {
            assert(searchResultsBuffer.tenukiLossForRole != -1);

            double tenukiLossResultBuffer[] = new double[tree.mNumRoles];

            for(int i = 0; i < tree.mNumRoles; i++)
            {
              tenukiLossResultBuffer[i] = (i == searchResultsBuffer.tenukiLossForRole ? 0 : 100);
            }

            //  Win here if the optional player does not play locally
            for (short index = 0; index < node.mNumChildren; index++)
            {
              if (node.mPrimaryChoiceMapping == null || node.mPrimaryChoiceMapping[index] == index)
              {
                Object choice = node.mChildren[index];

                ForwardDeadReckonLegalMoveInfo moveInfo = ((choice instanceof TreeEdge) ? ((TreeEdge)choice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)choice);

                TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
                if (edge != null && edge.getChildRef() != TreeNode.NULL_REF)
                {
                  TreeNode child = node.get(edge.getChildRef());
                  if (child != null && !child.mComplete)
                  {
                    for(int i = 0; i < searchResultsBuffer.numTenukiLossMoves; i++)
                    {
                      if (searchResultsBuffer.tenukiLossMoves[i] == moveInfo)
                      {
                        if (searchResultsBuffer.hasKnownWinDistances())
                        {
                          LOGGER.info(moveInfo.mMove.toString() + " is a global loss for " + tree.mRoleOrdering.roleIndexToRole(searchResultsBuffer.tenukiLossForRole) + " from seed move " + searchResultsBuffer.seedMove);
                          child.mCompletionDepth = (short)(node.getDepth()+searchResultsBuffer.atDepth-1);
                          child.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_LOSS;
                          assert(child.mCompletionDepth >= 0 && child.mCompletionDepth >= child.getDepth());
                        }
                        else
                        {
                          LOGGER.info(moveInfo.mMove.toString() + " is a local loss for " + tree.mRoleOrdering.roleIndexToRole(searchResultsBuffer.tenukiLossForRole) + " from seed move " + searchResultsBuffer.seedMove);
                          child.mCompletionDepth = (short)(node.getDepth()+searchResultsBuffer.atDepth-1);
                          child.mLocalSearchStatus = LocalSearchStatus.LOCAL_SEARCH_LOSS;
                          assert(child.mCompletionDepth >= 0 && child.mCompletionDepth >= child.getDepth());
                        }
                        break;
                      }
                    }
                  }
                }
              }
            }

            LOGGER.info("Storing tenuki-loss seed move for next priority search seed");

            //  Push out the next re-evaluation of what we should be searching as this line is looking interesting
            localSearchRefreshTime = System.currentTimeMillis() + MIN_LOCAL_SEARCH_REFRESH_PERIOD;

            priorityLocalSearchSeed = searchResultsBuffer.seedMove;
          }

          factorTrees[0].processNodeCompletions();
        }
      }

      localSearchResultProcessingAttemptSeq = 0;
      lastProcessedSearchResultSeq = lastQueuedSearchResultSeq;
    }
  }
}