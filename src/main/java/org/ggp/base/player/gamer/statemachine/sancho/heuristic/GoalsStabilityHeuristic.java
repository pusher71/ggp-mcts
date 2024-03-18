package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.SampleAverageMean;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.stats.PearsonCorrelation;

public class GoalsStabilityHeuristic implements Heuristic
{
  private SampleAverageMean[]   roleErrors = null;
  private PearsonCorrelation[]  roleCorrelations = null;
  private SampleAverageMean[]   averageGoalValues = null;
  private ForwardDeadReckonPropnetStateMachine stateMachine = null;
  private RoleOrdering          roleOrdering = null;
  private boolean               tuningComplete = false;

  public GoalsStabilityHeuristic()
  {
  }

  public double getGoalStability()
  {
    if ( !tuningComplete )
    {
      return 0;
    }

    double instability = 0;

    assert(tuningComplete);

    for(int roleIndex = 0; roleIndex < averageGoalValues.length; roleIndex++)
    {
      double roleNormalizedError = Math.sqrt(roleErrors[roleIndex].getAverage())/100;
      //  Negative correlations are treated like no correlation, which will give low stability
      //  This is handled this way because we really expect the realistic correlation range to be 0 to 1
      double roleCorrelation = Math.max(0, roleCorrelations[roleIndex].getCorrelation());
      double roleInstability = Math.sqrt(roleNormalizedError*(1-roleCorrelation));

      if ( roleInstability > instability )
      {
        instability = roleInstability;
      }
    }

    return 1 - instability;
  }

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                  RoleOrdering xiRoleOrdering)
  {
    stateMachine = xiStateMachine;
    roleOrdering = xiRoleOrdering;

    roleErrors = new SampleAverageMean[xiStateMachine.getRoles().size()];
    averageGoalValues = new SampleAverageMean[xiStateMachine.getRoles().size()];
    roleCorrelations = new PearsonCorrelation[xiStateMachine.getRoles().size()];
    for(int i = 0; i < averageGoalValues.length; i++)
    {
      roleErrors[i] = new SampleAverageMean();
      averageGoalValues[i] = new SampleAverageMean();
      roleCorrelations[i] = new PearsonCorrelation();
    }

    return true;
  }

  @Override
  public void tuningStartSampleGame()
  {
    for(int i = 0; i < averageGoalValues.length; i++)
    {
      averageGoalValues[i].clear();
    }
  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState,
                                       int xiChoosingRoleIndex)
  {
    for(int roleIndex = 0; roleIndex < averageGoalValues.length; roleIndex++)
    {
      int goalValue = stateMachine.getGoal(xiState, roleOrdering.roleIndexToRole(roleIndex));

      averageGoalValues[roleIndex].addSample(goalValue);
    }
  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                        int[] xiRoleScores)
  {
    for(int roleIndex = 0; roleIndex < averageGoalValues.length; roleIndex++)
    {
      int goalValue = stateMachine.getGoal(xiState, roleOrdering.roleIndexToRole(roleIndex));

      double diff = goalValue - averageGoalValues[roleIndex].getAverage();
      double squaredError = diff*diff;
      roleErrors[roleIndex].addSample(squaredError);
      roleCorrelations[roleIndex].sample(goalValue, averageGoalValues[roleIndex].getAverage());
    }
  }

  @Override
  public void tuningComplete()
  {
    tuningComplete = true;
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState,
                      TreeNode xiNode)
  {
    // TODO Auto-generated method stub

  }

  /**
   * Get the heuristic value for the specified state.
   *
   * @param xiState           - the state (never a terminal state).
   * @param xiPreviousState   - the previous state (can be null).
   * @param xiReferenceState  - state with which to compare to determine heuristic values
   */
  @Override
  public void getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                ForwardDeadReckonInternalMachineState xiPreviousState,
                                ForwardDeadReckonInternalMachineState xiReferenceState,
                                HeuristicInfo resultInfo)
  {
    // TODO Auto-generated method stub
  }

  @Override
  public boolean isEnabled()
  {
    //  Never enabled as a runtime heuristics - only for analysis
    return !tuningComplete;
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    return null;
  }

  @Override
  public boolean applyAsSimpleHeuristic()
  {
    // TODO Auto-generated method stub
    return false;
  }
}
