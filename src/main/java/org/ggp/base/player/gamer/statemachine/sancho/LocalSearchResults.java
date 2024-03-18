package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;

/**
 * @author steve
 * Information structure detailing local search results
 */
public class LocalSearchResults implements LocalRegionDefiner
{
  private static final Logger LOGGER       = LogManager.getLogger();

  /**
   * Role for whom a (local) forced win was found or -1 if none
   */
  public int winForRole;
  /**
   * Role for whom a (local) tenuki is a loss or -1 if none
   */
  public int tenukiLossForRole;
  /**
   * If the result is a tenuki-loss (implied by tenukiLossForRole != -1) then how many moves are
   * definite losses
   */
  public int  numTenukiLossMoves;
  /**
   * If the result is a tenuki-loss (implied by tenukiLossForRole != -1) then which moves are
   * definite losses
   */
  public final ForwardDeadReckonLegalMoveInfo[] tenukiLossMoves = new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR];
  /**
   * Seeding move for the local space
   */
  public ForwardDeadReckonLegalMoveInfo seedMove;
  /**
   * State from which the search began
   */
  public ForwardDeadReckonInternalMachineState startState;
  /**
   * State from which the start state was a child (or null)
   */
  public ForwardDeadReckonInternalMachineState choiceFromState;
  /**
   * Move found to be a win (or null if none)
   */
  public ForwardDeadReckonLegalMoveInfo winningMove;
  /**
   * Depth of search result found at
   */
  public int atDepth;

  ForwardDeadReckonLegalMoveInfo jointSearchSecondarySeed;
  int searchRadius;
  LocalRegionSearcher searchProvider;
  ForwardDeadReckonLegalMoveInfo[] winPath;
  ForwardDeadReckonLegalMoveSet[] relevantMovesForWin;
  boolean hasTerminalityCoupling = false;

  static boolean detailedTrace = false;

  /**
   * Make this object a shallow copy of source
   * @param source
   */
  public void copyFrom(LocalSearchResults source)
  {
    winForRole = source.winForRole;
    tenukiLossForRole = source.tenukiLossForRole;
    seedMove = source.seedMove;
    startState = source.startState;
    winningMove = source.winningMove;
    atDepth = source.atDepth;
    jointSearchSecondarySeed = source.jointSearchSecondarySeed;
    searchRadius = source.searchRadius;
    searchProvider = source.searchProvider;
    winPath = source.winPath;
    choiceFromState = source.choiceFromState;
    hasTerminalityCoupling = source.hasTerminalityCoupling;

    relevantMovesForWin = new ForwardDeadReckonLegalMoveSet[source.searchRadius+1];

    for(int i = 0; i <= source.searchRadius; i++)
    {
      relevantMovesForWin[i] = new ForwardDeadReckonLegalMoveSet(source.relevantMovesForWin[i]);
      relevantMovesForWin[i].copy(source.relevantMovesForWin[i]);
    }

    if ( tenukiLossForRole != -1 )
    {
      numTenukiLossMoves = source.numTenukiLossMoves;
      for(int i = 0; i < numTenukiLossMoves; i++)
      {
        tenukiLossMoves[i] = source.tenukiLossMoves[i];
      }
    }
  }

  @Override
  public boolean isLocal(ForwardDeadReckonLegalMoveInfo xiMove)
  {
    boolean result = (seedMove == null ? null : (searchProvider.getMoveCoInfluenceDistance(seedMove, xiMove) <= searchRadius));
    if ( jointSearchSecondarySeed != null )
    {
      result |= (searchProvider.getMoveCoInfluenceDistance(jointSearchSecondarySeed, xiMove) <= searchRadius);
    }
    return result;
  }

  @Override
  public boolean canInfluenceFoundResult(ForwardDeadReckonLegalMoveInfo xiMove)
  {
    int winningRole = (winForRole != -1 ? winForRole : (1 - tenukiLossForRole));
    //  TEMP until I can sort out the correct semantics
    //return true;
    if ( relevantMovesForWin == null )
    {
      return true;
    }


    //  TODO - a much tighter check than this is possible, since we only care about a move if
    //  it can prevent a played move, not just if it has a co-influenced base prop.  Hence the
    //  tighter test is the distance from the move to the legal-influencing base props of the played
    //  moves in the sequence
    for(int i = 1; i <= searchRadius; i++)
    {
      for(ForwardDeadReckonLegalMoveInfo relevantMove : relevantMovesForWin[i].getContents(searchProvider.roleOrdering.roleIndexToRawRoleIndex(winningRole)))
      {
        //  If there is terminality-coupling then any coinfluence of the two moves matters (or at least
        //  some do, and further analysis might be able to restrict which).  However, if there is no
        //  terminality coupling then a played move can only prevent a found win by preventing one of
        //  the moves, so legality distance is all that matters
        if ( hasTerminalityCoupling )
        {
          int coInfluenceDistance = searchProvider.getMoveCoInfluenceDistance(relevantMove, xiMove);
          if ( coInfluenceDistance < i + (i == searchRadius ? 1 : 0))
          {
            if ( detailedTrace )
            {
              LOGGER.info("  Relevant move " + i + ": " + relevantMove + " is at distance " + coInfluenceDistance + " and therefore has comnmon influence");
            }
            return true;
          }
        }

        //  Can also be the case that the queried move enabled legality of a required move in the sequence
        //  in which case it can influence it even though there are no co-influenced base props
        int legalityEnablementDistance = searchProvider.getMoveEnablementDistance(xiMove, relevantMove);
        if ( legalityEnablementDistance <= i )
        {
          if ( detailedTrace )
          {
            LOGGER.info("  Relevant move " + i + ": " + relevantMove + " is at legality-enablement distance " + legalityEnablementDistance + " and therefore can be influenced");
          }
          return true;
        }
        if ( detailedTrace )
        {
          LOGGER.info("  Relevant move " + i + ": " + relevantMove + " is at distance " + legalityEnablementDistance + " and therefore cannot influence");
        }
      }
    }

    return false;
  }

  @Override
  public boolean seedMayEnableResult()
  {
    return canInfluenceFoundResult(seedMove);
    //  TEMP until I can sort out the correct semantics
    //return true;
//    if ( winPath == null )
//    {
//      return true;
//    }
//
//    for(int i = 1; i <= searchRadius; i++)
//    {
//      ForwardDeadReckonLegalMoveInfo winPathMove = winPath[i];
//      if ( winPathMove != null && winPathMove.inputProposition != null )
//      {
//        //  Could this move have been enabled by the seed move?
//        //  Note <= here not < since the seed is assume to preceed
//        //  the found move path
//        if ( searchProvider.getMoveDistance(winPathMove, seedMove) <= searchRadius )
//        {
//          return true;
//        }
//        break;
//      }
//    }
//
//    return false;
  }

  @Override
  public int getMinWinDistance(ForwardDeadReckonLegalMoveInfo xiMove)
  {
    // TODO  - when we have goal distances
    return 0;
  }

  @Override
  public boolean hasKnownWinDistances()
  {
    // TODO  - when we have goal distances
    return false;
  }
}