package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Arrays;
import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class LocalRegionSearcher
{
  public interface LocalSearchController
  {
    boolean terminateSearch();
  }

  /**
   * @author steve
   * Interface through which the local searcher will notify any forced wins or forced
   * tenuki losses through
   */
  public interface LocalSearchResultConsumer
  {
    /**
     * Process the results of a local search
     * @param results
     */
    void  ProcessLocalSearchResult(LocalSearchResults results);
  }

  private static final Logger LOGGER       = LogManager.getLogger();

  private static final int MAX_BRANCHING_FACTOR = 100;
  private static final int MAX_DEPTH            = 20;
  //  Inn order to cater for situations where one role has a win at a certain depth
  //  but the other role has a win slightly deeper, but with forcing moves along the
  //  way that effectively make it shallower, we search force sequences as if they were
  //  not search-depth-increasing (to find the deeper-but-forced win at its effective
  //  depth).  This can quickly lead to an explosion in the size of the search space
  //  and remove the benefits of local search in the vast majority of cases where the
  //  forces are actually not relevant, and for this reason we restrict ourselves to
  //  short forced sequences only (currently single force move and its response).  This
  //  covers most cases in practice without too badly impacting on overall search
  //  times.
  private static final int MAX_SEQUENTIAL_FORCES_CONSIDERED = 1;

  private static final int FOCUS_DISTANCE = 4;

  private final ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private ForwardDeadReckonInternalMachineState startingState = null;
  private ForwardDeadReckonInternalMachineState choiceFromState = null;
  final RoleOrdering roleOrdering;
  private final int numRoles;
  private final LocalSearchController controller;

  private DependencyDistanceInfo moveDistances = null;
  private boolean                distancesAnalysed = false;

  private boolean                optionalRoleHasOddDepthParity;

  private final ForwardDeadReckonLegalMoveInfo[][] jointMove;
  private final ForwardDeadReckonLegalMoveInfo[][] chooserMoveChoiceStack;
  private final ForwardDeadReckonInternalMachineState[] childStateBuffer;
  private final ForwardDeadReckonLegalMoveInfo pseudoNoop;
  private final LocalSearchResultConsumer resultsConsumer;
  private final LocalSearchResults searchResult = new LocalSearchResults();
  private final boolean[] moveIsResponse;
  private final boolean[] moveIsEnabledBySequence;
  private final boolean[] moveIsForcedBySequence;
  private final boolean[][] chooserMoveChoiceIsResponse;

  private int numNodesSearched;
  private int currentDepth;
  private int firstSearchedRole;
  private ForwardDeadReckonLegalMoveInfo regionCentre;
  private boolean unconstrainedSearch;
  private final int[] optionalMoveKillerWeight;
  private final int[] NonOptionalMoveKillerWeight;

  private final int[] tenukiLossDepth;
  private final ForwardDeadReckonLegalMoveInfo[] tenukiLossSeeds;
  private final int[] tenukiLossNumLosingMoves;
  private final ForwardDeadReckonLegalMoveInfo[][] tenukiLossLosingMoves;
  private final ForwardDeadReckonLegalMoveInfo[][] knownWinPathMoves;
  private final boolean[] winPathCached;

  private final ForwardDeadReckonLegalMoveSet[][] relevantMoves;

  public LocalRegionSearcher(
                    ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                    RoleOrdering xiRoleOrdering,
                    LocalSearchController xiController,
                    LocalSearchResultConsumer xiResultsConsumer)
  {
    underlyingStateMachine = xiUnderlyingStateMachine;
    roleOrdering = xiRoleOrdering;
    controller = xiController;
    resultsConsumer = xiResultsConsumer;

    numRoles = underlyingStateMachine.getRoles().size();
    jointMove = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH+1][];
    chooserMoveChoiceStack = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH+1][];
    childStateBuffer = new ForwardDeadReckonInternalMachineState[MAX_DEPTH+1];
    tenukiLossDepth = new int[xiUnderlyingStateMachine.getRoles().size()];
    tenukiLossSeeds = new ForwardDeadReckonLegalMoveInfo[xiUnderlyingStateMachine.getRoles().size()];
    tenukiLossNumLosingMoves = new int[xiUnderlyingStateMachine.getRoles().size()];
    tenukiLossLosingMoves = new ForwardDeadReckonLegalMoveInfo[xiUnderlyingStateMachine.getRoles().size()][];
    knownWinPathMoves = new ForwardDeadReckonLegalMoveInfo[xiUnderlyingStateMachine.getRoles().size()][];
    winPathCached = new boolean[xiUnderlyingStateMachine.getRoles().size()];
    moveIsResponse = new boolean[MAX_DEPTH+1];
    moveIsEnabledBySequence = new boolean[MAX_DEPTH+1];
    moveIsForcedBySequence = new boolean[MAX_DEPTH+1];
    chooserMoveChoiceIsResponse = new boolean[MAX_DEPTH+1][];
    relevantMoves = new ForwardDeadReckonLegalMoveSet[MAX_DEPTH+1][];

    pseudoNoop = new ForwardDeadReckonLegalMoveInfo();
    pseudoNoop.mIsPseudoNoOp = true;

    optionalMoveKillerWeight = new int[underlyingStateMachine.getFullPropNet().getMasterMoveList().length];
    NonOptionalMoveKillerWeight = new int[underlyingStateMachine.getFullPropNet().getMasterMoveList().length];

    searchResult.searchProvider = this;
    searchResult.relevantMovesForWin = new ForwardDeadReckonLegalMoveSet[MAX_DEPTH+1];

    for(int i = 0; i <= MAX_DEPTH; i++ )
    {
      chooserMoveChoiceStack[i] = new ForwardDeadReckonLegalMoveInfo[MAX_BRANCHING_FACTOR];
      jointMove[i] = new ForwardDeadReckonLegalMoveInfo[numRoles];
      childStateBuffer[i] = underlyingStateMachine.createEmptyInternalState();
      chooserMoveChoiceIsResponse[i] = new boolean[MAX_BRANCHING_FACTOR];
      relevantMoves[i] = new ForwardDeadReckonLegalMoveSet[MAX_DEPTH+1];
      for(int j = 0; j <= MAX_DEPTH; j++)
      {
        relevantMoves[i][j] = new ForwardDeadReckonLegalMoveSet(underlyingStateMachine.getFullPropNet().getActiveLegalProps(0));
      }
      searchResult.relevantMovesForWin[i] = new ForwardDeadReckonLegalMoveSet(underlyingStateMachine.getFullPropNet().getActiveLegalProps(0));
    }

    for(int i = 0; i < tenukiLossLosingMoves.length; i++)
    {
      tenukiLossLosingMoves[i] = new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR];
      knownWinPathMoves[i] = new ForwardDeadReckonLegalMoveInfo[MAX_DEPTH+1];
    }
  }

  /**
   * Determine whether we can (usefully) perform local search on the current game
   * @return true if we can
   */
  public boolean canPerformLocalSearch()
  {
    if ( !distancesAnalysed )
    {
      moveDistances = generateMoveDistanceInformation();
      distancesAnalysed = true;
    }

    return (moveDistances != null);
  }

  /**
   * Decay killer move stats (between turns typically)
   */
  public void decayKillerStatistics()
  {
    LOGGER.info("Decaying killer stats");
    for(int i = 0; i < optionalMoveKillerWeight.length; i++)
    {
      optionalMoveKillerWeight[i] = (optionalMoveKillerWeight[i] + 9)/10;
      NonOptionalMoveKillerWeight[i] = (NonOptionalMoveKillerWeight[i] + 9)/10;
    }

    for(int i = 0; i < tenukiLossSeeds.length; i++)
    {
      tenukiLossSeeds[i] = null;
    }

    for(int i = 0; i < winPathCached.length; i++)
    {
      if ( winPathCached[i] )
      {
        if ( i == firstSearchedRole && knownWinPathMoves[i][1] != regionCentre )
        {
          LOGGER.info("Move played invalidates cached win for player " + i);
          winPathCached[firstSearchedRole] = false;
        }
        else
        {
          LOGGER.info("Retaining win path for player " + i + ":");
          for(int j = 1; j <= MAX_DEPTH && knownWinPathMoves[i][j] != null; j++)
          {
            LOGGER.info("  " + knownWinPathMoves[i][j]);
          }
        }
      }
    }
  }

  /**
   * Request a new search begins
   * @param xiStartingState - state to start searching from
   * @param xiChoiceFromState - state which was the rootm point the choice of seed was made from
   * @param xiRegionCentre - seed move to look for nearby sequences from
   * @param choosingRole - role to look for a win for first at each depth
   */
  public void setSearchParameters(
    ForwardDeadReckonInternalMachineState xiStartingState,
    ForwardDeadReckonInternalMachineState xiChoiceFromState,
    ForwardDeadReckonLegalMoveInfo xiRegionCentre,
    int choosingRole)
  {
    if ( moveDistances == null )
    {
      return;
    }

    regionCentre = xiRegionCentre;

    currentDepth = 1;
    numNodesSearched = 0;
    firstSearchedRole = choosingRole;

    for(int i = 0; i < tenukiLossDepth.length; i++)
    {
      tenukiLossDepth[i] = MAX_DEPTH;
    }

    unconstrainedSearch = (xiRegionCentre == null);

    startingState = xiStartingState;
    choiceFromState = xiChoiceFromState;

//    if ( xiRegionCentre != null && xiRegionCentre.toString().contains("3 4 2 3"))
//    {
//      currentDepth=10;
//      choosingRole = 1;
//    }
    LOGGER.info("Starting new search with seed move: " + xiRegionCentre + " and first choosing role " + choosingRole);
  }

  /**
   * Encapsulated iterated deepening search to run synchronously in one call
   * @param xiStartingState
   * @param xiSeed
   * @param maxDepth
   * @return final score (0, 100 , 50).  If 0 or 100 result is a forced win.  50 implies indeterminate
   */
  public int completeResultSearchToDepthFromSeed(
    ForwardDeadReckonInternalMachineState xiStartingState,
    ForwardDeadReckonLegalMoveInfo xiSeed,
    int maxDepth)
  {
    if ( maxDepth > MAX_DEPTH )
    {
      maxDepth = MAX_DEPTH;
    }

    //  For now don't try to use local search - just global scope
    unconstrainedSearch = true;

    //  Search with iterative deepening for each role in turn
    regionCentre = xiSeed;
    numNodesSearched = 0;
    for(int i = 0; i < tenukiLossDepth.length; i++)
    {
      tenukiLossDepth[i] = MAX_DEPTH;
    }

    for(int depth = 1; depth <= maxDepth; depth++)
    {
      currentDepth = depth;

      for(int role = 0; role < numRoles; role++)
      {
        int score = searchToDepth(xiStartingState, depth, maxDepth, role, null, false);
        if ( score == (role == 0 ? 0 : 100) )
        {
          return score;
        }
      }
    }

    return 50;
  }

  /**
   * Iterate the depth of the search by one
   * @return true if the search terminated (found a win or exceeded max allowable depth)
   */
  public boolean iterate()
  {
    //LOGGER.info("Local move search beginning for depth " + currentDepth);

    if ( tenukiLossSeeds[firstSearchedRole] != null )
    {
      firstSearchedRole = 1-firstSearchedRole;
      LOGGER.info("Forcing first searched role to " + firstSearchedRole + " due to known possible win for opposing role");
    }

    for(int role = 0; role < numRoles; role++)
    {
      //  We don;t look for forced wins by the player who already has a forced loss if they
      //  tenuki at a lower depth.  This **could** miss a win whose starting move is also
      //  a key defensive move, but trying to allow that case while still not accidentally declaring
      //  forced wins that are actually tenuki losses at a shallower depth is quite hard
//      if ( tenukiLossDepth[1-optionalRole] < currentDepth )
//      {
//        //LOGGER.info("Skipping search for optional role " + roleOrdering.roleIndexToRole(optionalRole));
//        continue;
//      }

      //LOGGER.info("Searching with optional role " + optionalRole);
      int score;
      int optionalRole = 1-(role+firstSearchedRole)%2;
      boolean resultFound = false;

      //  If we know there is a tenuki loss for one player, then before searching for forced wins at this depth for
      //  that player, check that there is no forced win for the opponent with the seed that found the tenuki loss
//      if ( tenukiLossSeeds[1-role] != null )
//      {
//        optionalRole = 1-role;
//        jointMove[0][0] = tenukiLossSeeds[optionalRole];
//        LOGGER.info("Performing safety check search for optional role " + optionalRole + " at depth " + currentDepth);
//        score = searchToDepth(startingState, 1, currentDepth, optionalRole);
//
//        resultFound = (score == (optionalRole==0 ? 0 : 100));
//        if ( resultFound )
//        {
//          LOGGER.info("NOT safe!");
//        }
//      }
      if ( !resultFound )
      {
//        if ( optionalRole == 1 && currentDepth == 11 && regionCentre.toString().contains("3 4 3 3"))
//        {
//          System.out.println("!");
//          //trace = true;
//        }

        jointMove[0][0] = regionCentre;
        if ( tenukiLossSeeds[1-optionalRole] != null && tenukiLossSeeds[1-optionalRole] != regionCentre && getMoveCoInfluenceDistance(tenukiLossSeeds[1-optionalRole], regionCentre) > currentDepth)
        {
          jointMove[0][1] = tenukiLossSeeds[1-optionalRole];
          LOGGER.debug("Performing joint search for optional role " + optionalRole + " at depth " + currentDepth + " with secondary seed " + tenukiLossSeeds[1-optionalRole]);
        }
        else
        {
          jointMove[0][1] = null;
          LOGGER.debug("Performing regular search for optional role " + optionalRole + " at depth " + currentDepth);
        }
        score = searchToDepth(startingState, 1, currentDepth, optionalRole, null, false);
        resultFound = (score == (optionalRole==0 ? 0 : 100));
//        if ( !resultFound && optionalRole == 1 && currentDepth == 9 && regionCentre.toString().contains("3 4 3 3"))
//        {
//          System.out.println("!");
//          //trace = true;
//        }
      }
      if ( resultFound )
      {
        LOGGER.info("Local search finds win at depth " + currentDepth + " for role " + roleOrdering.roleIndexToRole(1-optionalRole) + " with " + numNodesSearched + " states visited");
        LOGGER.info("Last examined move trace:");
        for(int i = 1; i <= currentDepth; i++)
        {
          LOGGER.info(Arrays.toString(jointMove[i]));
        }
//        if ( optionalRole == 0 && regionCentre.toString().contains("4 2 3 3"))
//        {
//          System.out.println("!!");
//        }

        //  Preserve the results now before potentially doing a verificational search against
        //  a known tenuki loss (as this will overwrite the search information)
        searchResult.atDepth = currentDepth;
        searchResult.winForRole = 1-optionalRole;
        searchResult.tenukiLossForRole = -1;
        searchResult.seedMove = jointMove[0][0];
        searchResult.jointSearchSecondarySeed = jointMove[0][1];
        searchResult.winningMove = jointMove[1][1-optionalRole];
        searchResult.startState = startingState;
        searchResult.searchRadius = currentDepth;
        searchResult.choiceFromState = choiceFromState;

        searchResult.winPath = new ForwardDeadReckonLegalMoveInfo[currentDepth+1];
        for( int i = 0; i <= currentDepth; i++)
        {
          searchResult.winPath[i] = jointMove[i][1-optionalRole];
          searchResult.relevantMovesForWin[i].copy(relevantMoves[1][i]);
        }

        //  One final check is needed.  If we found a tenuki loss for the apparently winning role one ply earlier
        //  then it is possible that the supposed winning move actually loses to the tenuki-loss already
        //  identified (in which case a definite win would be found at the NEXT depth if this move were played)
        //  for the opponent.  In this one case we need to verify the previous depth is not a loss with a fixed first move
        if ( tenukiLossSeeds[1-optionalRole] == regionCentre && tenukiLossDepth[1-optionalRole] < currentDepth )
        {
          if ( searchToDepth(startingState, 1, tenukiLossDepth[1-optionalRole], 1-optionalRole, jointMove[1][1-optionalRole], false) == (optionalRole==0 ? 100 : 0))
          {
            LOGGER.info("Apparent win actually is a tenuki-loss at the previous depth - ignoring!");
            resultFound = false;
          }
        }

        if ( resultFound )
        {
          if ( resultsConsumer != null )
          {
            resultsConsumer.ProcessLocalSearchResult(searchResult);
          }

          //  Treat the winning move the same as a tenuki loss at the previous level to
          //  force searching relative to it in other alternatives
          for(int i = 0; i < jointMove[1].length; i++)
          {
            if ( jointMove[1][i] != null && jointMove[1][i].mInputProposition != null )
            {
              tenukiLossSeeds[1-optionalRole] = jointMove[1][i];
              break;
            }
          }

          //  Cache the recorded win path and treat all moves on it as priority searches
          //  until it is invalidated
          //  TODO - does this obviate the need for the use of the tenuki loss seed above?
          for( int i = 1; i <= currentDepth; i++)
          {
            knownWinPathMoves[1-optionalRole][i] = searchResult.winPath[i];
          }
          for( int i = currentDepth+1; i <= MAX_DEPTH; i++)
          {
            knownWinPathMoves[1-optionalRole][i] = null;
          }
          winPathCached[1-optionalRole] = true;
          LOGGER.info("Caching new win path for role " + (1-optionalRole));

          return true;
        }
      }
    }

    if ( controller != null && controller.terminateSearch() )
    {
      LOGGER.debug("Local search terminated at depth " + currentDepth + " with " + numNodesSearched + " states visited");
    }
    else
    {
      LOGGER.debug("Local search completed at depth " + currentDepth + " with " + numNodesSearched + " states visited");
      currentDepth++;

      if ( currentDepth > MAX_DEPTH )
      {
        return true;
      }
    }

    return false;
  }

  private void clearWinPathRelevantMoves(int depth)
  {
    for(int i = depth; i <= currentDepth; i++)
    {
      relevantMoves[depth][i].clear();
    }
  }

  /*
   * Search to specified depth, returning:
   *  0 if role 1 win
   *  100 if role 0 win
   *  else 50
   */
  private int searchToDepth(ForwardDeadReckonInternalMachineState state, int depth, int maxDepth, int optionalRole, ForwardDeadReckonLegalMoveInfo forcedMoveChoice, boolean pathIncludesTenuki)
  {
    numNodesSearched++;

    if ( underlyingStateMachine.isTerminal(state))
    {
      int result = underlyingStateMachine.getGoal(state, roleOrdering.roleIndexToRole(0));
      return result;
    }

    if ( depth > maxDepth || (controller != null && controller.terminateSearch()) )
    {
      return 50;
    }

    clearWinPathRelevantMoves(depth);

    int choosingRole = -1;
    int numChoices = 0;
    ForwardDeadReckonLegalMoveInfo nonChooserMove = null;
    boolean tenukiPossible = true;
    int numLegalChooserMoves = 0;

    for(int i = 0; i < numRoles; i++)
    {
      Role role = roleOrdering.roleIndexToRole(i);
      Collection<ForwardDeadReckonLegalMoveInfo> legalMoves = underlyingStateMachine.getLegalMoves(state, role);

      if ( legalMoves.iterator().next().mInputProposition == null )
      {
        if ( depth == 1 && i == 0 )
        {
          optionalRoleHasOddDepthParity = (i != optionalRole);

          //  Wins can only occur on the move of the putative winning player so we
          //  cannot have a forced win for the non-optional player with a given
          //  max search depth that was not findable at a lesser depth unless the
          //  max depth is such that the final ply choice is the non-optional player
          if ( (maxDepth%2 == 0) != optionalRoleHasOddDepthParity )
          {
            return 50;
          }
//          if ( optionalRoleHasOddDepthParity )
//          {
//            LOGGER.info("Non-optional role chooses at depth 1");
//          }
//          else
//          {
//            LOGGER.info("Optional role chooses at depth 1");
//          }
        }
        nonChooserMove = legalMoves.iterator().next();
      }
      else
      {
        if ( depth == 1 && i == 0 )
        {
          optionalRoleHasOddDepthParity = (i == optionalRole);

          //  Wins can only occur on the move of the putative winning player so we
          //  cannot have a forced win for the non-optional player with a given
          //  max search depth that was not findable at a lesser depth unless the
          //  max depth is such that the final ply choice is the non-optional player
          if ( (maxDepth%2 == 0) != optionalRoleHasOddDepthParity )
          {
            return 50;
          }
//          if ( optionalRoleHasOddDepthParity )
//          {
//            LOGGER.info("Non-optional role chooses at depth 1");
//          }
//          else
//          {
//            LOGGER.info("Optional role chooses at depth 1");
//          }
        }
        numChoices = getLocalMoves(legalMoves, chooserMoveChoiceStack[depth], depth, maxDepth, optionalRole);
        choosingRole = i;
        numLegalChooserMoves = legalMoves.size();
        tenukiPossible = (numChoices < numLegalChooserMoves);
      }
    }

    assert(choosingRole != -1);

    boolean incomplete = false;

    moveIsForcedBySequence[depth] = false;

    //  At depth 1 consider the optional tenuki first as a complete result
    //  there will allow cutoff in the MCTS tree (in principal)
    //  We also must consider a tenuki if there has been no tenuki on the path so far
    //  in order to discover forced response requirements
    boolean considerForcingProbe = !pathIncludesTenuki && (maxDepth < currentDepth + MAX_SEQUENTIAL_FORCES_CONSIDERED*2);
    if ( choosingRole == optionalRole && (tenukiPossible || depth == 1 || considerForcingProbe) && (numChoices == 0 || tenukiLossDepth[optionalRole] > currentDepth) && forcedMoveChoice == null )
    {
      jointMove[depth][1-choosingRole] = nonChooserMove;
      jointMove[depth][choosingRole] = pseudoNoop;
      moveIsResponse[depth] = false;
      moveIsEnabledBySequence[depth] = false;

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole, null, true);

      if ( childValue != (optionalRole == 0 ? 0 : 100) )
      {
        //  Tenuki is not a forced win for the non-optional role.  This means that there is
        //  nothing decisive in the local-search-space
        return (choosingRole == 0 ? 100 : 0);
      }

      if ( depth == 1 && tenukiLossDepth[optionalRole] > currentDepth )
      {
        for(int j = depth+1; j <= maxDepth; j++)
        {
          relevantMoves[depth][j].merge(relevantMoves[depth+1][j]);
        }

        LOGGER.info("Tenuki is a loss for " + (optionalRole == 0 ? "us" : "them") + " at depth " + currentDepth);

        searchResult.atDepth = currentDepth;
        searchResult.winForRole = -1;
        searchResult.tenukiLossForRole = optionalRole;
        searchResult.seedMove = jointMove[0][0];
        searchResult.jointSearchSecondarySeed = jointMove[0][1];
        searchResult.winningMove = null;
        searchResult.startState = startingState;
        searchResult.searchRadius = currentDepth;
        //searchResult.winPath = null;
        //searchResult.choiceFromState = null;
        //searchResult.relevantMovesForWin = null;
        searchResult.choiceFromState = choiceFromState;
        searchResult.winPath = new ForwardDeadReckonLegalMoveInfo[currentDepth+1];
        for( int i = 0; i <= currentDepth; i++)
        {
          searchResult.winPath[i] = jointMove[i][1-optionalRole];
          searchResult.relevantMovesForWin[i].copy(relevantMoves[1][i]);

          assert(searchResult.relevantMovesForWin[i] != null);
        }

        //  Work out which moves are definite losses because they cannot influence the win path
        searchResult.numTenukiLossMoves = 0;
        for(int i = 0; i < numLegalChooserMoves; i++)
        {
          ForwardDeadReckonLegalMoveInfo move = chooserMoveChoiceStack[depth][i];
          boolean canInfluence = searchResult.canInfluenceFoundResult(move);
          boolean isLocal = searchResult.isLocal(move);
          int minWinDistance = searchResult.getMinWinDistance(move);

          LOGGER.info("    Move " + move + ": canInfluence=" + canInfluence + ", isLocal=" + isLocal + ", minWinDistance=" + minWinDistance);
          if ( !canInfluence && (!searchResult.hasKnownWinDistances() || minWinDistance > searchResult.atDepth))
          {
            searchResult.tenukiLossMoves[searchResult.numTenukiLossMoves++] = move;
            tenukiLossLosingMoves[optionalRole][tenukiLossNumLosingMoves[optionalRole]++] = move;
          }
        }

        tenukiLossDepth[optionalRole] = currentDepth;
        tenukiLossSeeds[optionalRole] = jointMove[0][0];

        if ( resultsConsumer != null )
        {
          resultsConsumer.ProcessLocalSearchResult(searchResult);
        }
      }

      //  If tenuki is a loss that implies that a response is forced, so increase the max depth of
      //  the search in this branch, so that forces are considered to be non-depth increasing for
      //  the purposes of inclusion in a certain search radius.  Doing this is necessary to make
      //  the strict iterated deepening have a reliable result (else a win can be falsely claimed
      //  for one role, when the opponent has one which is deeper, but in which the responses are forced
      //  preventing the shallower first role win from being played)
      if ( maxDepth < MAX_DEPTH-1 && maxDepth < currentDepth + MAX_SEQUENTIAL_FORCES_CONSIDERED*2 )
      {
        maxDepth += 2;
      }

      moveIsForcedBySequence[depth] = true;
    }

    for(int i = 0; i < numChoices; i++)
    {
      ForwardDeadReckonLegalMoveInfo choice = chooserMoveChoiceStack[depth][i];
      if ( forcedMoveChoice != null && choice != forcedMoveChoice )
      {
        continue;
      }

      //  No need to consider known losses if tenuki-loss was determined at a shallower search level
      if ( depth == 1 && tenukiLossDepth[choosingRole] < currentDepth )
      {
        boolean skipFound = false;
        for(int j = 0; j < tenukiLossNumLosingMoves[choosingRole]; j++)
        {
          if ( choice == tenukiLossLosingMoves[choosingRole][j])
          {
            skipFound = true;
            break;
          }
        }

//        if ( skipFound )
//        {
//          LOGGER.info("Skipping search of move " + choice + " as it is a known loss from shallower tenuki loss analysis");
//          continue;
//        }
//
//        LOGGER.info("Considering move " + choice);
      }

      boolean isReponse = chooserMoveChoiceIsResponse[depth][i];

      //  You cannot win with a response, since this was only enabled by opponent moves
      //  which we have been forced to respond to and is otherwise out of scope at this
      //  depth of the search.  Allowing responses to also be wins gives false positives
      //  because the opponent might have been denied a refuting move in the search due
      //  to it being out of scope of the supposed efficient sequence.
      //  In fact the non-optional role's last TWO moves must be non-responses because
      //  if the winning move was possible without the last response move then it could
      //  have been played in place of that last response move
      if ( isReponse && choosingRole != optionalRole && depth > maxDepth-4 )
      {
//        if ( trace && depth < 7 )
//        {
//          String depthTab = "                     ".substring(0, depth);
//          LOGGER.info(depthTab + depth + ": " + chooserMoveChoiceStack[depth][i].move + " is a response that cannot support a win");
//        }
        continue;
      }

      jointMove[depth][1-choosingRole] = nonChooserMove;
      jointMove[depth][choosingRole] = choice;
      moveIsResponse[depth] = isReponse;

      moveIsEnabledBySequence[depth] = false;
      if ( choosingRole == optionalRole && depth > 1 )
      {
        //  TEMP - this loop should go to j > 0 really
        for(int j = depth-1; j >= depth-1; j -= 2)
        {
          if ( getMoveEnablementDistance(jointMove[j][1-choosingRole], jointMove[depth][choosingRole]) == 1 /*||
               getMoveCoInfluenceDistance(jointMove[j][1-choosingRole], jointMove[depth][choosingRole]) == 1*/ )
          {
            moveIsEnabledBySequence[depth] = true;
            break;
          }
        }
      }

      underlyingStateMachine.getNextState(state, null, jointMove[depth], childStateBuffer[depth]);

      int childValue = searchToDepth(childStateBuffer[depth], depth+1, maxDepth, optionalRole, null, pathIncludesTenuki);

//      if ( trace && depth < 7 )
//      {
//        String depthTab = "                     ".substring(0, depth);
//        LOGGER.info(depthTab + depth + ": " + jointMove[depth][choosingRole].move + " scores " + childValue + (isReponse ? "(response)" : "") + (moveIsEnabledBySequence[depth] ? "(enabled)" : "") + (moveIsForcedBySequence[depth] ? "(forced)" : ""));
//      }

      if ( childValue == (choosingRole == 0 ? 100 : 0) || (childValue == 50 && choosingRole == optionalRole) )
      {
//        if ( depth == 1 && choosingRole == optionalRole && maxDepth==10 )
//        {
//          LOGGER.info("    depth 1 move " + jointMove[1][choosingRole] + " is not a loss");
//          LOGGER.info("Last examined move trace:");
//          for(int j = 1; j <= currentDepth; j++)
//          {
//            LOGGER.info(Arrays.toString(jointMove[j]));
//          }
//        }

        //  Complete result.
        //  Note this includes draws for the optional role since we're only interested in forced wins
        //  for the non-optional role
//        LOGGER.info("Complete result: " + childValue + " (" + moveDesc + ")");
        int killerValue = 1<<(currentDepth-depth);
        if ( choosingRole == optionalRole)
        {
          optionalMoveKillerWeight[jointMove[depth][choosingRole].mMasterIndex] += killerValue;
        }
        else
        {
          NonOptionalMoveKillerWeight[jointMove[depth][choosingRole].mMasterIndex] += killerValue;

          //  This is the path we would take from here so it is relevant to the solution
          relevantMoves[depth][depth].add(jointMove[depth][choosingRole]);

          if ( depth < maxDepth )
          {
            //  As are all the descendant relevant moves found in solving this node
            for(int j = depth+1; j <= maxDepth; j++)
            {
              relevantMoves[depth][j].merge(relevantMoves[depth+1][j]);
            }
          }
        }
        return (choosingRole == 0 ? 100 : 0);//childValue;
      }
      else if ( choosingRole == optionalRole && depth < maxDepth )
      {
        //  This path is one we have to be able to handle from here so it is relevant
        for(int j = depth+1; j <= maxDepth; j++)
        {
          relevantMoves[depth][j].merge(relevantMoves[depth+1][j]);
        }
      }

      incomplete |= (childValue != (choosingRole == 0 ? 0 : 100));
    }

    if ( numChoices == 0 && choosingRole != optionalRole )
    {
      //  No moves available for non-optional role implies this branch completely
      //  searched with no win found
      return 50;
    }

    if ( !incomplete )
    {
      //assert(depth!=1);
      //LOGGER.info("@" + depth + " choosing role " + " - complete result due to all child completion");
      return (choosingRole == 0 ? 0 : 100);
    }

    return 50;
  }

  public int getMoveCoInfluenceDistance(ForwardDeadReckonLegalMoveInfo from, ForwardDeadReckonLegalMoveInfo to)
  {
    return moveDistances.moveCoInfluenceDistances[from.mMasterIndex][to.mMasterIndex];
  }

  public int getMoveEnablementDistance(ForwardDeadReckonLegalMoveInfo from, ForwardDeadReckonLegalMoveInfo to)
  {
    return moveDistances.moveEnablingDistances[from.mMasterIndex][to.mMasterIndex];
  }

  private int heuristicValue(ForwardDeadReckonLegalMoveInfo move, int depth, ForwardDeadReckonLegalMoveInfo previousLocalMove, boolean forOptionalRole)
  {
//    if ( move.toString().contains("8 6 7 7"))
//    {
//      return 100000;
//    }
    //  If we're joint searching with a secondary seed and a legal move at depth 1 is exactly the
    //  secondary seed move choose it first
    if ( depth == 1 && jointMove[0][1] != null && jointMove[0][1].mMasterIndex == move.mMasterIndex )
    {
      return Integer.MAX_VALUE;
    }
    if ( forOptionalRole )
    {
      return optionalMoveKillerWeight[move.mMasterIndex];
    }
    return NonOptionalMoveKillerWeight[move.mMasterIndex];
  }

  private int getLocalMoves(Collection<ForwardDeadReckonLegalMoveInfo> allMoves, ForwardDeadReckonLegalMoveInfo[] localMoves, int depth, int maxDistance, int optionalRole)
  {
    int numChoices = 0;
    boolean jointSearch = (jointMove[0][1] != null);
    boolean chosenMovesAreForOptionalRole = ((depth%2==1) == optionalRoleHasOddDepthParity);
    int choosingRole = (chosenMovesAreForOptionalRole ? optionalRole : 1-optionalRole);
    int notChoiceIndex = allMoves.size()-1;

    assert ( moveDistances != null );

    for(ForwardDeadReckonLegalMoveInfo move : allMoves)
    {
      boolean include = false;
      //boolean priorityInclude = false;
      boolean isNonResponse = false;
      ForwardDeadReckonLegalMoveInfo plyMove = null;

      if ( !unconstrainedSearch )
      {
        boolean seenNonResponsePly = false;
        boolean seenUnforcedEnablingPly = false;
        boolean terminateTrackBack = false;

        //  Unconditionally include known win path moves
        if ( winPathCached[choosingRole] )
        {
          for(int i = 1; i <= MAX_DEPTH && knownWinPathMoves[choosingRole][i] != null; i++)
          {
            if (knownWinPathMoves[choosingRole][i] == move)
            {
              include = true;
              isNonResponse = true;
              //priorityInclude = true;
              break;
            }
          }
        }

        if ( !include )
        {
          //  All non-optional moves played must be in scope of the seed
          //  if we have a unique seed (else the overall result is not dependent on
          //  the seed).  Optional moves have to be allowed to go outside this boundary
          //  or else refutations of non-optional moves played may not be found and a
          //  false positive can result from the overall search for a win
          if ( !chosenMovesAreForOptionalRole && !jointSearch && jointMove[0][0] != null && moveDistances.moveCoInfluenceDistances[jointMove[0][0].mMasterIndex][move.mMasterIndex] > maxDistance )
          {
            continue;
          }
  //        if ( move.toString().contains("5 6 6 7") || move.toString().contains("8 6 8 7"))
  //        {
  //          continue;
  //        }

          for(int i = depth-1; i >= 0; i--)
          {
  //          boolean plyMoveFound = false;
            boolean isDistanceGating = false;
            boolean plyIsResponse = false;
            boolean previousWasForced = false;
            boolean isOptionalRolePly = (optionalRoleHasOddDepthParity == (i%2 == 1));

            //  The rules for a joint search are less tightly constrainable than those for a regular search,
            //  but essentially a joint search is one where a valid non-optional sequence from each role is
            //  interleaved with optional responses to the other player's sequence.  Because we cannot
            //  determine unambiguously if any given move is part of an (optional) response sequence or a
            //  (non-optional) efficient sequence we can only make fairly wide constraints
            if ( !jointSearch )
            {
              if ( i == 0 && (!chosenMovesAreForOptionalRole || depth==1) )
              {
                //  The first non-optional role move is distance gated by the seed, but the first
                //  optional role move is only gated by the seed if it is the first move of the
                //  sequence (optional role moves that are within distance of the seed but are
                //  not dependent on any non-optional role move made need not be searched - they
                //  may potentially be wins for the optional role, but only if they are independently
                //  of the non-optional moves played, and thus discoverable when searched with role
                //  optionality reversed)
                isDistanceGating = true;
                plyIsResponse = (!chosenMovesAreForOptionalRole && tenukiLossDepth[1-optionalRole] < MAX_DEPTH && depth < 3);
                terminateTrackBack = true;
                previousWasForced = (i == 1 && (moveIsResponse[i] || moveIsForcedBySequence[i]));
              }
              else if ( chosenMovesAreForOptionalRole )
              {
                //  Optional role moves are distance gated by the last non-optional role
                //  non-response move or the last enabled optional role move that was not forced
                if ( (!isOptionalRolePly && (depth < 3 || !moveIsResponse[i]) && !seenNonResponsePly) || (isOptionalRolePly && !seenUnforcedEnablingPly && moveIsEnabledBySequence[i]))
                {
                  isDistanceGating = true;
                  plyIsResponse = isOptionalRolePly;
                  seenNonResponsePly |= !isOptionalRolePly;
                  seenUnforcedEnablingPly |= isOptionalRolePly && !moveIsForcedBySequence[i];

                  //  Can stop if we've moved past the last possible enablers
                  terminateTrackBack = (seenUnforcedEnablingPly && seenNonResponsePly);
                }
              }
              else
              {
                //  Non-optional role moves are gated on the last non-response non-optional
                //  role move and the last optional enabled move
                if ( (!isOptionalRolePly && !moveIsResponse[i] && !seenNonResponsePly) || (isOptionalRolePly && !seenUnforcedEnablingPly && moveIsEnabledBySequence[i]))
                {
                  isDistanceGating = true;
                  plyIsResponse = isOptionalRolePly;
                  seenNonResponsePly = !isOptionalRolePly;
                  seenUnforcedEnablingPly |= isOptionalRolePly;
                  terminateTrackBack = (seenUnforcedEnablingPly && seenNonResponsePly);
                  //previousWasForced &= (moveIsResponse[i] || moveIsForcedBySequence[i] || isOptionalRolePly);
                  if ( !isOptionalRolePly && (moveIsResponse[i] || moveIsForcedBySequence[i]))
                  {
                    isDistanceGating = false;
                  }
                }
              }
            }
            else
            {
              if ( i == 0 && depth < 2 )
              {
                //  The first move for either role is gated by the seed(s)
                isDistanceGating = true;
                plyIsResponse = false;
              }
              else if ( (depth%2) != (i%2) )
              {
                //  Any move can be a response to the previous (only) opponent move
                if ( i == depth-1 )
                {
                  isDistanceGating = true;
                  plyIsResponse = false;
                }
              }
              else
              {
                //  A move can follow on from any previous move of the same role
                isDistanceGating = true;
                plyIsResponse = false;
              }
            }
  //          if ( i == 0 || !isOptionalRolePly || jointSearch)
  //          {
  //            //  All non-optional role moves are distance gating
  //            isDistanceGating = true;
  //            plyIsResponse = moveIsResponse[i];
  //            seenNonResponsePly = !plyIsResponse;
  //          }
  //          else if ( i >= depth-2 && isOptionalRolePly )
  //          {
  //            //  The most recent optional role move is distance gating
  //            isDistanceGating = true;
  //            plyIsResponse = true;
  //          }

            if ( isDistanceGating )
            {
              for(int j = 0; j < jointMove[i].length; j++)
              {
                if ( jointMove[i][j].mInputProposition != null )
                {
                  plyMove = jointMove[i][j];
                  break;
                }
              }
              if ( plyMove != null )
              {
                int maxAllowableDistance = ((chosenMovesAreForOptionalRole || previousWasForced) ? maxDistance-i : Math.min(maxDistance-i, FOCUS_DISTANCE));
  //              plyMoveFound = true;
                int distance = moveDistances.moveCoInfluenceDistances[plyMove.mMasterIndex][move.mMasterIndex];
                boolean includeAtThisPly = (distance <= maxAllowableDistance);
                if ( i == 0 && jointSearch )
                {
                  includeAtThisPly |= (moveDistances.moveCoInfluenceDistances[jointMove[0][1].mMasterIndex][move.mMasterIndex] <= maxAllowableDistance);
                }
                if ( chosenMovesAreForOptionalRole && tenukiLossDepth[1-optionalRole] < MAX_DEPTH )
                {
                  includeAtThisPly |= (moveDistances.moveCoInfluenceDistances[tenukiLossSeeds[1-optionalRole].mMasterIndex][move.mMasterIndex] <= tenukiLossDepth[1-optionalRole]+1);
                }
                //  If the move is already included and this ply is not an apparent response
                //  then the inclusion we already have must have been as a response we which are
                //  about to override (since it's part of an efficient sequence from the last
                //  chooser move as well).  However, if its right on the fringe of the efficient
                //  sequence moves (max distance allowed from previous chooser move) do not do this
                //  but rather keep it flagged as a response.  This addresses cases which occur on the fringe
                //  where the move is a forced response, but was legitimate as a regular efficient
                //  sequence also, and the actual desirable sequence follows from the previous move
                //  but goes out of scope of THIS move a one deeper ply.
  //              if ( includeAtThisPly && !plyIsResponse && include )
  //              {
  //                break;
  //              }

                include |= includeAtThisPly;

                if ( includeAtThisPly )
                {
                  //  If we include the move due to a ply move that is not just a response
                  //  to an optional role move then the overall included move is not a response
                  isNonResponse |= !plyIsResponse;
                }
                if ( terminateTrackBack)//seenNonResponsePly && i <= depth-2 )
                {
                  break;
                }
              }
  //            if ( plyMoveFound && i < depth-1 )
  //            {
  //              break;
  //            }
            }
          }
        }
      }
      else
      {
        include = true;
      }

      if ( include )
      {
        int insertAt;
        int heuristicValue = heuristicValue(move, depth, plyMove, (depth%2==1) == optionalRoleHasOddDepthParity);
//        if ( depth ==1  )
//        {
//          LOGGER.info("Depth 1 move choice " + move + " has killer value " + heuristicValue + " for " + (((depth%2==1) == optionalRoleHasOddDepthParity) ? "optional role" : "non-optional role"));
//        }
        for(insertAt = 0; insertAt < numChoices; insertAt++)
        {
          if ( heuristicValue > heuristicValue(localMoves[insertAt], depth, plyMove, (depth%2==1) == optionalRoleHasOddDepthParity))
          {
            break;
          }
        }

        for(int i = numChoices; i > insertAt; i--)
        {
          localMoves[i] = localMoves[i-1];
          chooserMoveChoiceIsResponse[depth][i] = chooserMoveChoiceIsResponse[depth][i-1];
        }

        localMoves[insertAt] = move;
        chooserMoveChoiceIsResponse[depth][insertAt] = !isNonResponse;
        numChoices++;
      }
      else
      {
        //  Not included (but legal) moves are added at the end of the array
        //  as we still need to know what they are when analysing tenuki losses
        localMoves[notChoiceIndex--] = move;
      }
    }

    return numChoices;
  }

  private DependencyDistanceInfo generateMoveDistanceInformation()
  {
    DependencyDistanceAnalyser distanceAnalyser = new DependencyDistanceAnalyser(underlyingStateMachine);
    LOGGER.info("Begin analysing move distances...");
    DependencyDistanceInfo distanceInfo = distanceAnalyser.getDistanceInfo();
    LOGGER.info("Completed analysing move distances...");

    return distanceInfo;
  }
}
