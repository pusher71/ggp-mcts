package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.statemachine.sancho.LocalRegionSearcher.LocalSearchController;
import org.ggp.base.player.gamer.statemachine.sancho.LocalRegionSearcher.LocalSearchResultConsumer;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * @author steve
 *
 *  This class is responsible for local search to determine whether there are forced win/loss
 *  consequences of a given seed move from a given state
 */
public class MoveConsequenceSearcher implements Runnable, LocalSearchController
{
  private static final Logger LOGGER       = LogManager.getLogger();

  private boolean mTerminateRequested = false;
  private volatile int searchRequested = -1;
  private int searchProcessing = -1;
  private int nextSearch = 0;
  private ForwardDeadReckonInternalMachineState currentState;
  private ForwardDeadReckonInternalMachineState choiceFromState;
  private ForwardDeadReckonLegalMoveInfo        seedMove;
  private int                                   choosingRole;
  private boolean resetStats = false;
  private final Thread mThread;

  private final LocalRegionSearcher regionSearcher;
  private final String mLogName;

  /**
   * @param xiUnderlyingStateMachine - state machine instance the move consequence searcher can use -
   *                                   this must not be shared with other uses on other threads
   * @param xiRoleOrdering           - canonical role order mappings
   * @param logName                  - the name of the log
   * @param resultConsumer           - interface by which results of the search are communicated
   */
  public MoveConsequenceSearcher(
                    ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                    RoleOrdering xiRoleOrdering,
                    String logName,
                    LocalSearchResultConsumer resultConsumer)
  {
    regionSearcher = new LocalRegionSearcher(xiUnderlyingStateMachine, xiRoleOrdering, this, resultConsumer);
    mLogName = logName;

    if ( regionSearcher.canPerformLocalSearch() )
    {
      mThread = new Thread(this, "Move Consequence Processor");
      mThread.setDaemon(true);
      mThread.start();
    }
    else
    {
      mThread = null;
    }
  }

  /**
   * @return whether local searching is enabled in the current game
   */
  public boolean isEnabled()
  {
    return regionSearcher.canPerformLocalSearch();
  }

  /**
   * Terminate the local search thread
   */
  public void stop()
  {
    mTerminateRequested = true;

    if ( mThread != null )
    {
      synchronized(this)
      {
        this.notifyAll();
      }

      try
      {
        mThread.join(2000);
        if ( mThread.isAlive() )
        {
          LOGGER.warn("Local search processor failed to stop cleanly - interrupting");
          mThread.interrupt();
          mThread.join(5000);
        }
      }
      catch (InterruptedException lEx)
      {
        LOGGER.warn("Unexpectedly interrupted whilst stopping local search processor");
      }
    }
  }

  /**
   * Start a new local search
   * @param startState        - root state for the new search
   * @param xiChoiceFromState - state the start state is a child of
   * @param seed              - move being used as a locality seed
   * @param xiChoosingRole    - role for whom we are looking for a forced win
   * @param isNewTurn         - whether this is a search around the last played move on a new turn
   * @param forceSearchReset  - if true force starting from a 0 depth even if the search matches the currently executing one
   */
  public void newSearch(ForwardDeadReckonInternalMachineState startState,
                        ForwardDeadReckonInternalMachineState xiChoiceFromState,
                        ForwardDeadReckonLegalMoveInfo seed,
                        int xiChoosingRole,
                        boolean isNewTurn,
                        boolean forceSearchReset)
  {
    synchronized(this)
    {
      //  If we're being asked for the same search again just continue
      if ( seedMove == null || seedMove.mMasterIndex != seed.mMasterIndex || forceSearchReset || !startState.equals(currentState) )
      {
        LOGGER.info("New local search request - move " + ((seedMove == null || seedMove.mMasterIndex != seed.mMasterIndex) ?  "differs" : "does not differ") + ", state " + (startState.equals(currentState) ? "does not differ" : "differs"));
        currentState = new ForwardDeadReckonInternalMachineState(startState);
        if ( xiChoiceFromState != null )
        {
          choiceFromState = new ForwardDeadReckonInternalMachineState(xiChoiceFromState);
        }
        else
        {
          choiceFromState = null;
        }
        seedMove = seed;
        choosingRole = xiChoosingRole;

        searchRequested = ++nextSearch;

        this.notifyAll();
      }
      else
      {
        LOGGER.info("New search request ignored - matching state and seed move");
      }
      if ( isNewTurn )
      {
        LOGGER.info("Is new turn");
        resetStats |= isNewTurn;
      }
    }
  }

  /**
   * Stop the current search
   */
  public void endSearch()
  {
    synchronized(this)
    {
      searchRequested = -1;
    }
  }

  @Override
  public void run()
  {
    // Register this thread.
    ThreadContext.put("matchID", mLogName);
    ThreadControl.registerSearchThread();

    if ( !regionSearcher.canPerformLocalSearch() )
    {
      LOGGER.info("Local search not supported for this game");
      return;
    }

    while(!mTerminateRequested)
    {
      synchronized(this)
      {
        if ( searchRequested == -1 )
        {
          try
          {
            this.wait();
          }
          catch (InterruptedException e)
          {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }

        if ( searchRequested != searchProcessing )
        {
          //  Start a new search
          regionSearcher.setSearchParameters(currentState, choiceFromState, seedMove, choosingRole);
          searchProcessing = searchRequested;

          if ( resetStats )
          {
            regionSearcher.decayKillerStatistics();
            resetStats = false;
          }
        }
      }

      if ( regionSearcher.iterate() )
      {
        // Current search complete
        synchronized(this)
        {
          if ( searchRequested == searchProcessing )
          {
            searchRequested = -1;
          }
        }
      }
    }

    LOGGER.info("Local search thread exitting");
  }

  @Override
  public boolean terminateSearch()
  {
    return (mTerminateRequested || searchProcessing != searchRequested);
  }
}
