package org.ggp.base.util.statemachine.playoutPolicy;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * @author steve
 *  Version of the goal-greedy playout policy that will pop back up the playout tree up to N levels
 *  if no acceptable move is found
 *  N is currently set to 2 which seems to be an empirically good compromise, but in future
 *  could be dynamically tuned based on observed playout rate
 */
public class PlayoutPolicyGoalGreedyWithPop extends PlayoutPolicyGoalGreedy
{
  private boolean policyFailedHasFailed = false;

  /**
   * @param xiStateMachine - state machine instance this policy will be used from
   */  public PlayoutPolicyGoalGreedyWithPop(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    super(xiStateMachine);
  }

   @Override
   public IPlayoutPolicy cloneFor(ForwardDeadReckonPropnetStateMachine xiStateMachine)
   {
     return new PlayoutPolicyGoalGreedyWithPop(xiStateMachine);
   }

   @Override
   public void noteNewPlayout()
   {
     policyFailedHasFailed = false;
   }

  @Override
  public boolean popStackOnAllUnacceptableMoves(int xiPopDepth)
  {
    //  Just allow pop to a depth of 2
    boolean canPop = (xiPopDepth <= 1);

    if ( !canPop )
    {
      policyFailedHasFailed = true;
    }

    return canPop;
  }

  @Override
  public boolean isAcceptableState(ForwardDeadReckonInternalMachineState xiToState,
                                   int xiRoleIndex)
  {
    if ( policyFailedHasFailed )
    {
      return true;
    }

    return super.isAcceptableState(xiToState, xiRoleIndex);
  }

  @Override
  public String toString()
  {
    return "Goal Greedy with pop";
  }
}
