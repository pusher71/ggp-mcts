package org.ggp.base.util.statemachine.playoutPolicy;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.StateMachineFilter;

/**
 * @author steve
 * Playout policy that preferentially plays goal-increasing moves
 */
public class PlayoutPolicyGoalGreedy implements IPlayoutPolicy
{
  private final ForwardDeadReckonPropnetStateMachine stateMachine;
  private final int[] currentStateScores;
  private ForwardDeadReckonInternalMachineState currentState = null;
  private final int[] latchedScoreRangeBuffer;

  /**
   * @param xiStateMachine - state machine instance this policy will be used from
   */
  public PlayoutPolicyGoalGreedy(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    stateMachine = xiStateMachine;
    currentStateScores = new int[xiStateMachine.getNumRoles()];
    latchedScoreRangeBuffer = new int[2];
  }

  @Override
  public void noteCurrentState(ForwardDeadReckonInternalMachineState state,
                               ForwardDeadReckonLegalMoveSet legalMoves,
                               StateMachineFilter factor,
                               int moveIndex,
                               ForwardDeadReckonLegalMoveInfo[] moveHistory,
                               ForwardDeadReckonInternalMachineState[] stateHistory)
  {
    int roleIndex = 0;

    currentState = state;
    for(Role role : stateMachine.getRoles())
    {
      currentStateScores[roleIndex++] = stateMachine.getGoal(role);
    }
  }

  @Override
  public IPlayoutPolicy cloneFor(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    return new PlayoutPolicyGoalGreedy(xiStateMachine);
  }

  @Override
  public boolean requiresMoveHistory()
  {
    return false;
  }

  @Override
  public boolean requiresStateHistory()
  {
    return true;
  }

  @Override
  public ForwardDeadReckonLegalMoveInfo selectMove(int roleIndex)
  {
    return null;
  }

  @Override
  public boolean isAcceptableMove(ForwardDeadReckonLegalMoveInfo xiCandidate,
                                  int xiRoleIndex)
  {
    return true;
  }

  @Override
  public boolean isAcceptableState(ForwardDeadReckonInternalMachineState xiToState,
                                   int xiRoleIndex)
  {
    Role role = stateMachine.getRoles().get(xiRoleIndex);
    int candidateScore = stateMachine.getGoal(xiToState, role);
    if ( stateMachine.isTerminal(xiToState))
    {
      //  Only allow terminality at the maximum achievable score
      stateMachine.getLatchedScoreRange(currentState, role, latchedScoreRangeBuffer);
      return (candidateScore >= latchedScoreRangeBuffer[1]);
    }

    return (candidateScore > currentStateScores[xiRoleIndex]);
  }

  @Override
  public String toString()
  {
    return "Goal Greedy";
  }

  @Override
  public boolean popStackOnAllUnacceptableMoves(int xiPopDepth)
  {
    return false;
  }

  @Override
  public void noteNewPlayout()
  {
  }

  @Override
  public boolean terminatePlayout()
  {
    return false;
  }

  @Override
  public void noteCompletePlayout(int xiLength,
                                  ForwardDeadReckonLegalMoveInfo[] xiMoves,
                                  ForwardDeadReckonInternalMachineState[] xiStates)
  {
    // Nothing to do here for this policy
  }

  @Override
  public void noteNewTurn()
  {
    // TODO Auto-generated method stub

  }
}
