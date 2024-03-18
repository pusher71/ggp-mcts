package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.GoalsCalculator;

/**
 * @author steve
 *  Goals calculator for games where the goal values are 0, 50 or 100 depending
 *  on which role has more of a proposition subset
 *  Only supports 2 player games currently
 */
public class MajorityPropositionGoalsCalculator extends MajorityCalculator implements Heuristic
{
  private final Map<Role,ForwardDeadReckonInternalMachineState> roleMasks;
  private final Map<Role,ForwardDeadReckonInternalMachineState> latchedRoleMasks;
  private ForwardDeadReckonInternalMachineState                 stateBuffer = null;
  private final Role                                            ourRole;

  /**
   * Construct new goals calculator
   */
  protected MajorityPropositionGoalsCalculator(ForwardDeadReckonPropnetStateMachine xiStateMachine, Role xiOurRole)
  {
    super(xiStateMachine, xiOurRole);
    roleMasks = new HashMap<>();
    latchedRoleMasks = new HashMap<>();
    ourRole = xiOurRole;
  }

  /**
   * Construct a functional close of a specified instance that may be used in a thread safe
   * manner relative to the original to calculate goal values
   * @param master - master copy to instantiate a functional clone of
   */
  private MajorityPropositionGoalsCalculator(MajorityPropositionGoalsCalculator master)
  {
    super(master.stateMachine, master.ourRole);
    roleMasks = master.roleMasks;
    latchedRoleMasks = master.latchedRoleMasks;
    ourRole = master.ourRole;
  }

  /**
   * Specify base propositions that are included in role count
   * @param role - role to add for
   * @param mask - mask of the pops to count for the specified role
   */
  public void addRoleMask(Role role, ForwardDeadReckonInternalMachineState mask)
  {
    roleMasks.put(role, mask);
  }

  @Override
  public GoalsCalculator createThreadSafeReference()
  {
    return new MajorityPropositionGoalsCalculator(this);
  }

  /**
   * Swap the masks between the (must be 2) roles
   */
  @Override
  public void reverseRoles()
  {
    assert(roleMasks.size()==2);

    Role firstRole = null;
    Role secondRole = null;
    ForwardDeadReckonInternalMachineState firstMask = null;
    ForwardDeadReckonInternalMachineState secondMask = null;

    for(Entry<Role, ForwardDeadReckonInternalMachineState> e : roleMasks.entrySet())
    {
      if (firstRole == null)
      {
        firstRole = e.getKey();
        firstMask = e.getValue();
      }
      else
      {
        secondRole = e.getKey();
        secondMask = e.getValue();
      }
    }

    roleMasks.clear();
    roleMasks.put(firstRole, secondMask);
    roleMasks.put(secondRole, firstMask);
  }

  @Override
  public String getName()
  {
    return "Proposition set cardinality";
  }

  @Override
  protected int getCount(ForwardDeadReckonInternalMachineState xiState,
                         Role xiRole)
  {
    if (stateBuffer == null)
    {
      stateBuffer = new ForwardDeadReckonInternalMachineState(xiState);
    }
    else
    {
      stateBuffer.copy(xiState);
    }

    stateBuffer.intersect(roleMasks.get(xiRole));
    return (int)stateBuffer.size();
  }

  @Override
  public boolean scoresAreLatched(ForwardDeadReckonInternalMachineState xiState)
  {
    //  No guarantee that counts cannot go down as well as up with this analysis
    //  so cannot determine latching
    return false;
  }

  @Override
  public Heuristic getDerivedHeuristic()
  {
    //  If any of the propositions being counted are latches we can derive a heuristic
    for(Entry<Role, ForwardDeadReckonInternalMachineState> e : roleMasks.entrySet())
    {
      ForwardDeadReckonInternalMachineState positiveLatchMask = stateMachine.mLatches.getPositiveBaseLatches();
      if (positiveLatchMask != null)
      {
        ForwardDeadReckonInternalMachineState intersection = new ForwardDeadReckonInternalMachineState(positiveLatchMask);

        intersection.intersect(e.getValue());
        if (intersection.size() > 0)
        {
          latchedRoleMasks.put(e.getKey(), intersection);
        }
      }
    }

    if (!latchedRoleMasks.isEmpty())
    {
      return this;
    }

    return null;
  }

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                  RoleOrdering xiRoleOrdering)
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void tuningStartSampleGame()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState,
                                       int xiChoosingRoleIndex)
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                        int[] xiRoleScores)
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void tuningComplete()
  {
    // TODO Auto-generated method stub

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
    if (latchedRoleMasks != null)
    {
      int ourLatchCount = 0;
      int theirLatchCount = 0;

      for(Entry<Role, ForwardDeadReckonInternalMachineState> e : latchedRoleMasks.entrySet())
      {
        if (e.getKey().equals(ourRole))
        {
          ourLatchCount += xiState.intersectionSize(e.getValue());
          ourLatchCount -= xiPreviousState.intersectionSize(e.getValue());
        }
        else
        {
          theirLatchCount += xiState.intersectionSize(e.getValue());
          theirLatchCount -= xiPreviousState.intersectionSize(e.getValue());
        }
      }

      if (ourLatchCount > theirLatchCount)
      {
        resultInfo.heuristicValue[0] = 100;
        resultInfo.heuristicValue[1] = 0;
      }
      else if (ourLatchCount < theirLatchCount)
      {
        resultInfo.heuristicValue[0] = 0;
        resultInfo.heuristicValue[1] = 100;
      }
      else
      {
        resultInfo.heuristicValue[0] = 50;
        resultInfo.heuristicValue[1] = 50;
      }

      resultInfo.heuristicWeight = 5*Math.abs(ourLatchCount - theirLatchCount);
    }
    else
    {
      resultInfo.heuristicWeight = 0;
    }

    resultInfo.treatAsSequenceStep = false;
  }

  @Override
  public boolean isEnabled()
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean applyAsSimpleHeuristic()
  {
    return false;
  }
}
