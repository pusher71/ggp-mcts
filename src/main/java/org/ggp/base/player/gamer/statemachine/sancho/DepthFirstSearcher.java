package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * A very basic depth-first searcher with alpha-beta pruning.
 *
 * - Only works for non-simultaneous play games (although strict alternation is not required.)
 * - Only really useful for 2-player, fixed-sum games.
 *   - For 3+ players, assumes that they collaborate against us.
 *   - For non-fixed sum games, assumes that our opponent simply wants to give us the smallest score.
 *
 * TODO
 *
 * - Identify the set of best-scoring moves and return them with the score
 * - Multi-threading
 * - Interruptible (for restarting if no solution is found before the end of the turn)
 * - Ordering hints for early moves, taken from MCTS/RAVE (which can massively cut down the work required)
 * - Consider other algorithms (e.g. SCOUT, NegaScout & MTD-f) along with heuristics and iterative deepening (IDDFS).
 */
public class DepthFirstSearcher
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final int MIN_VALUE = 0;
  private static final int MAX_VALUE = 100;

  private final ForwardDeadReckonPropnetStateMachine mStateMachine;
  private final Role mRole;
  private final int mNumRoles;

  private int mStatesConsidered;
  private int mTerminalStatesConsidered;
  private int mBestCompleteDepth;

  /**
   * Per-stack-frame variables that are created as members to avoid object allocation during recursion.
   */
  private ForwardDeadReckonInternalMachineState[] mStackState =
                                           new ForwardDeadReckonInternalMachineState[MCTSTree.MAX_SUPPORTED_TREE_DEPTH];
  private ForwardDeadReckonLegalMoveInfo[][] mStackJointMove =
                                                new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_TREE_DEPTH][];
  private ForwardDeadReckonLegalMoveInfo[][] mStackLegals =
                                                new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_TREE_DEPTH][];

  /**
   * Create a depth-first searcher.
   *
   * @param xiStateMachine - the state machine to use.
   * @param xiRole - our role.
   */
  public DepthFirstSearcher(ForwardDeadReckonPropnetStateMachine xiStateMachine, Role xiRole)
  {
    mStateMachine = xiStateMachine;
    mRole = xiRole;
    mNumRoles = xiStateMachine.getRoles().size();

    for (int lii = 0; lii < MCTSTree.MAX_SUPPORTED_TREE_DEPTH; lii++)
    {
      mStackState[lii]     = mStateMachine.createEmptyInternalState();
      mStackJointMove[lii] = new ForwardDeadReckonLegalMoveInfo[mNumRoles];
      mStackLegals[lii]    = new ForwardDeadReckonLegalMoveInfo[MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR];
    }
  }

  /**
   * Perform a depth-first search from the specified state.
   *
   * @param xiState - the state.
   */
  public void search(ForwardDeadReckonInternalMachineState xiState)
  {
    LOGGER.info("Starting DFS");

    mStatesConsidered = 0;
    mTerminalStatesConsidered = 0;
    mBestCompleteDepth = MCTSTree.MAX_SUPPORTED_TREE_DEPTH;

    mStackState[0] = xiState;
    int lValue = search(0, MIN_VALUE, MAX_VALUE);
    LOGGER.info("DFS complete with value " + lValue +
                " after " + mStatesConsidered + " states of which " + mTerminalStatesConsidered + " were terminal");
  }

  /**
   * Internal recursing function to perform a depth-first search.
   *
   * @param xiDepth - the current depth below the requested search root.  Also defines the explicit stack variables to
   *                  use.
   */
  private int search(int xiDepth, int xiAlpha, int xiBeta)
  {
    mStatesConsidered++;

    if (mStateMachine.isTerminal(mStackState[xiDepth]))
    {
      mTerminalStatesConsidered++;

      if (xiDepth < mBestCompleteDepth)
      {
        mBestCompleteDepth = xiDepth;
        report();
      }

      return mStateMachine.getGoal(mRole);
    }

    ForwardDeadReckonLegalMoveSet lLegals = mStateMachine.getLegalMoveSet(mStackState[xiDepth]);
    int lRoleWithChoice = -1;
    int lNumChoices = -1;
    for (int lii = 0; lii < mNumRoles; lii++)
    {
      // Store a legal move for this role.  For all roles but one, this will be the only move.  For the role with a
      // choice, this will be the first legal move.
      mStackJointMove[xiDepth][lii] = lLegals.getContents(lii).iterator().next();

      // Check if this is the role with a choice.
      if (lLegals.getNumChoices(lii) > 1)
      {
        assert(lRoleWithChoice == -1) : "More than 1 role has a choice";
        lRoleWithChoice = lii;
        lNumChoices = 0;

        // Copy out the legals before they're destroyed in the recursive call.
        Iterator<ForwardDeadReckonLegalMoveInfo> lIterator = lLegals.getContents(lii).iterator();
        while (lIterator.hasNext())
        {
          mStackLegals[xiDepth][lNumChoices++] = lIterator.next();
        }
      }
    }

    if (lRoleWithChoice == -1)
    {
      // No role had a choice.  Return the value of the only child.
      mStateMachine.getNextState(mStackState[xiDepth], null, mStackJointMove[xiDepth], mStackState[xiDepth + 1]);
      return search(xiDepth + 1, xiAlpha, xiBeta);
    }

    // A role had a choice.  If it was us, return the maximum value of all children.  If it wasn't, return the minimum.
    int lValue;
    if (lRoleWithChoice == 0)
    {
      // Our choice.
      lValue = MIN_VALUE;
      for (int lii = 0; lii < lNumChoices; lii++)
      {
        // Set the move for the role with a choice.  (All the others are set already.)
        mStackJointMove[xiDepth][lRoleWithChoice] = mStackLegals[xiDepth][lii];

        // Get the next state and do a recursive (depth-first) search.
        mStateMachine.getNextState(mStackState[xiDepth], null, mStackJointMove[xiDepth], mStackState[xiDepth + 1]);
        lValue = Math.max(lValue, search(xiDepth + 1, xiAlpha, xiBeta));
        xiAlpha = Math.max(xiAlpha, lValue);
        if (xiBeta <= xiAlpha) break; // Beta-cut
      }
    }
    else
    {
      // Their choice.
      lValue = MAX_VALUE;
      for (int lii = 0; lii < lNumChoices; lii++)
      {
        // Set the move for the role with a choice.  (All the others are set already.)
        mStackJointMove[xiDepth][lRoleWithChoice] = mStackLegals[xiDepth][lii];

        // Get the next state and do a recursive (depth-first) search.
        mStateMachine.getNextState(mStackState[xiDepth], null, mStackJointMove[xiDepth], mStackState[xiDepth + 1]);
        lValue = Math.min(lValue, search(xiDepth + 1, xiAlpha, xiBeta));
        xiBeta = Math.min(xiBeta, lValue);
        if (xiBeta <= xiAlpha) break; // Alpha-cut
      }
    }

    if (xiDepth <= mBestCompleteDepth)
    {
      mBestCompleteDepth = xiDepth;
      report();
    }

    return lValue;
  }

  private void report()
  {
    LOGGER.info("DFS has best completion depth of " + mBestCompleteDepth + " after " + mStatesConsidered + " states");
  }
}
