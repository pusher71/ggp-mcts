package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class TargetedSolutionStatePlayer
{
  private static final Logger LOGGER = LogManager.getLogger();

  private class AStarNode implements Comparable<AStarNode>
  {
    private ForwardDeadReckonInternalMachineState state;
    private AStarNode                             parent;
    private Move                                  move;
    private int                                   pathLength;
    private int                                   heuristicCost = -1;

    public AStarNode(ForwardDeadReckonInternalMachineState xiState,
                     AStarNode xiParent,
                     Move xiMove)
    {
      this.state = new ForwardDeadReckonInternalMachineState(xiState);
      this.parent = xiParent;
      this.move = xiMove;

      pathLength = (xiParent == null ? 0 : xiParent.pathLength + 1);
    }

    public AStarNode getParent()
    {
      return parent;
    }

    public Move getMove()
    {
      return move;
    }

    public ForwardDeadReckonInternalMachineState getState()
    {
      return state;
    }

    public int getPriority()
    {
      return pathLength + heuristicCost();
    }

    private int heuristicCost()
    {
      if ( heuristicCost == -1 )
      {
        ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(state);

        temp.intersect(mTargetState);

        heuristicCost = (int)(mTargetState.size() - temp.size());
      }

      return heuristicCost;
    }

    @Override
    public int compareTo(AStarNode o)
    {
      return getPriority() - o.getPriority();
    }
  }

  /**
   * The underlying state machine.
   */
  private final ForwardDeadReckonPropnetStateMachine mUnderlyingStateMachine;

  /**
   * Target state we must reach to achieve solution.
   */
  final ForwardDeadReckonInternalMachineState        mTargetState;

  /**
   * The gamer.
   */
  private final StateMachineGamer                    mGamer;

  /**
   * Construct a targeted-state puzzle solution solver for use when the solution state is known.
   *
   * @param xiStateMachine - underlying state machine
   * @param xiState - the target (solution) state.
   * @param xiGamer - StateMachineGamer from which current state can be queried
   */
  public TargetedSolutionStatePlayer(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                     MachineState xiState,
                                     StateMachineGamer xiGamer)
  {
    mUnderlyingStateMachine = xiStateMachine;
    mGamer                  = xiGamer;

    mTargetState = mUnderlyingStateMachine.createInternalState(xiState);
    mTargetState.intersect(mUnderlyingStateMachine.getNonControlMask());
  }

  /**
   * Attempt to solve the puzzle using A*.
   *
   * @param solutionScoreThreshold - score required to be considered as an acceptable solution.
   * @param timeout - time to search until (max).
   *
   * @return solution plan if found, else null.
   */
  public Collection<Move> attemptAStarSolve(int solutionScoreThreshold, long timeout)
  {
    int bestGoalFound = -1;
    List<Move> AStarSolutionPath     = null;
    PriorityQueue<AStarNode> AStarFringe = new PriorityQueue<>();

    AStarFringe.add(new AStarNode(mUnderlyingStateMachine.createInternalState(mGamer.getCurrentState()), null, null));

    ForwardDeadReckonInternalMachineState steplessStateMask = mUnderlyingStateMachine.getNonControlMask();

    int largestDequeuePriority = -1;
    int numStatesProcessed = 0;

    Set<ForwardDeadReckonInternalMachineState> AStarVisitedStates = new HashSet<>();
    ForwardDeadReckonLegalMoveInfo[] jointMove = new ForwardDeadReckonLegalMoveInfo[1];
    ForwardDeadReckonInternalMachineState stateBuffer = new ForwardDeadReckonInternalMachineState(steplessStateMask);
    ForwardDeadReckonInternalMachineState steplessStateBuffer = new ForwardDeadReckonInternalMachineState(steplessStateMask);

    while (!AStarFringe.isEmpty() && System.currentTimeMillis() < timeout)
    {
      numStatesProcessed++;
      AStarNode node = AStarFringe.remove();

      if (node.getPriority() > largestDequeuePriority)
      {
        largestDequeuePriority = node.getPriority();
      }

      if (mUnderlyingStateMachine.isTerminal(node.getState()))
      {
        int goalValue = mUnderlyingStateMachine.getGoal(node.getState(), mGamer.getRole());

        if (goalValue > bestGoalFound)
        {
          AStarSolutionPath = new LinkedList<>();

          //  Construct solution path
          while (node != null && node.getMove() != null)
          {
            AStarSolutionPath.add(0, node.getMove());
            node = node.getParent();
          }

          bestGoalFound = goalValue;
          if (goalValue == 100)
          {
            break;
          }
        }
      }

      // Expand the node and add children to the fringe.
      Collection<ForwardDeadReckonLegalMoveInfo> childMoves = mUnderlyingStateMachine.getLegalMoves(node.getState(),
                                                                                                    mGamer.getRole());

      for (ForwardDeadReckonLegalMoveInfo moveInfo : childMoves)
      {
        jointMove[0] = moveInfo;

        mUnderlyingStateMachine.getNextState(node.getState(), null, jointMove, stateBuffer);

        steplessStateBuffer.copy(stateBuffer);
        steplessStateBuffer.intersect(steplessStateMask);

        if (!AStarVisitedStates.contains(steplessStateBuffer))
        {
          AStarNode newChild = new AStarNode(stateBuffer, node, moveInfo.mMove);
          AStarFringe.add(newChild);

          AStarVisitedStates.add(new ForwardDeadReckonInternalMachineState(steplessStateBuffer));
        }
      }
    }

    LOGGER.info("A* processed " + numStatesProcessed + " states");
    return (bestGoalFound >= solutionScoreThreshold ? AStarSolutionPath : null);
  }
}
