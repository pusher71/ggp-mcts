package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlFunction;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * @author steve
 * A psueudo-heuristic which leverages the heuristic evaluation mechanism to
 * asses a potential goal emulator for correctness
 */
public class MajorityGoalsHeuristic implements Heuristic
{
  private static final Logger LOGGER = LogManager.getLogger();

  private ForwardDeadReckonPropnetStateMachine stateMachine = null;
  private boolean                              tuningComplete = true;
  private MajorityCalculator                   goalsCalculator = null;
  private RoleOrdering                         roleOrdering = null;
  private boolean                              reverseRoles = false;
  private boolean                              roleReversalFixed = false;
  private boolean                              predictionsMatch = true;
  private Heuristic                            derivedHeuristic = null;

  /**
   * A heuristic used to validate a majority score goals emulator
   */
  public MajorityGoalsHeuristic()
  {
  }

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                  RoleOrdering xiRoleOrdering)
  {
    stateMachine = xiStateMachine;
    roleOrdering = xiRoleOrdering;

    //  Only currently supported for 2 player games
    int numRoles = xiStateMachine.getRoles().size();
    if ( numRoles != 2 )
    {
      return false;
    }

    //  Is it plausible that the goals of this game are based on a majority count of some
    //  base prop sentence?
    for(PolymorphicProposition[] goals : xiStateMachine.getFullPropNet().getGoalPropositions().values())
    {
      for ( PolymorphicProposition p : goals)
      {
        int goalValue = Integer.parseInt(p.getName().getBody().get(1).toString());

        if ( goalValue != 0 && goalValue != 50 && goalValue != 100 )
        {
          return false;
        }
      }
    }

    GdlConstant basePropGoalFnName = null;
    List<Set<String>> paramRanges = new ArrayList<>();
    Map<Role, Set<PolymorphicProposition>> roalGoalsSupportingBaseProps = new HashMap<>();

    //  For each goal find the set of baseprops it is immediately dependent on
    for(Role role : xiStateMachine.getRoles())
    {
      Set<PolymorphicProposition> derivedFromBaseProps = new HashSet<>();

      for(PolymorphicProposition p : xiStateMachine.getFullPropNet().getGoalPropositions().get(role))
      {
        Set<PolymorphicComponent> processedComponents = new HashSet<>();
        recursiveFindFeedingBaseProps( stateMachine.getFullPropNet(), p, derivedFromBaseProps, processedComponents);
      }

      for(PolymorphicProposition p : derivedFromBaseProps)
      {
        GdlSentence propName = p.getName();

        if (propName.arity() == 1 &&
            propName.getBody().get(0) instanceof GdlFunction)
        {
          GdlFunction propFn = (GdlFunction)propName.getBody().get(0);
          GdlConstant propFnName = propFn.getName();

          if ( basePropGoalFnName == null )
          {
            assert(propFnName != null);
            basePropGoalFnName = propFnName;

            for (int i = 0; i < propFn.arity(); i++)
            {
              paramRanges.add(new HashSet<String>());
            }
          }
          else if ( basePropGoalFnName != propFnName )
          {
            return false;
          }

          for (int i = 0; i < propFn.arity(); i++)
          {
            paramRanges.get(i).add(propFn.getBody().get(i).toString());
          }
        }
      }

      roalGoalsSupportingBaseProps.put(role, derivedFromBaseProps);
    }

    if ( basePropGoalFnName == null )
    {
      return false;
    }

    //  Is one of the param ranges plausibly a role?
    int bestParamIndex = -1;

    for(int i = 0; i < paramRanges.size(); i++)
    {
      if ( paramRanges.get(i).size() == numRoles )
      {
        bestParamIndex = i;
        break;
      }
    }

    if ( bestParamIndex == -1 )
    {
      return false;
    }

    //  Split into sets for each role
    Map<Role,Set<PolymorphicProposition>> roleSets = new HashMap<>();
    String previousRoleVal = null;
    Map<Role,String> roleParamValues = new HashMap<>();

    for(Role role : xiStateMachine.getRoles())
    {
      String roleVal = null;
      Set<PolymorphicProposition> roleSet = new HashSet<>();

      for(PolymorphicProposition p : roalGoalsSupportingBaseProps.get(role))
      {
        GdlTerm propBodyTerm = p.getName().getBody().get(0);
        if ( propBodyTerm instanceof GdlFunction )
        {
          GdlFunction propFn = (GdlFunction)propBodyTerm;
          String roleParamValue = propFn.getBody().get(bestParamIndex).toString();

          if ( roleVal == null && !roleParamValue.equals(previousRoleVal))
          {
            roleVal = roleParamValue;
            roleParamValues.put(role, roleVal);
          }

          if ( roleParamValue.equals(roleVal) )
          {
            roleSet.add(p);
          }
        }
      }

      roleSets.put(role, roleSet);
      previousRoleVal = roleVal;
    }

    //  Sometimes the goal network infers a prop state from the absence of others,
    //  so having identified the function and the role parameter add all that match
    for(PolymorphicProposition p : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      GdlTerm propBodyTerm = p.getName().getBody().get(0);
      if ( propBodyTerm instanceof GdlFunction )
      {
        GdlFunction propFn = (GdlFunction)propBodyTerm;

        if ( propFn.getName().equals(basePropGoalFnName))
        {
          String roleParamValue = propFn.getBody().get(bestParamIndex).toString();

          for(Role role : xiStateMachine.getRoles())
          {
            if ( roleParamValue.equals(roleParamValues.get(role)) )
            {
              roleSets.get(role).add(p);
            }
          }
        }
      }
    }

    goalsCalculator = MajorityCountGoalsCalculator.createMajorityCountGoalsCalculator(stateMachine, roleOrdering.roleIndexToRole(0), roleSets);
    if ( goalsCalculator == null )
    {
      MajorityPropositionGoalsCalculator propGoalsCalculator = new MajorityPropositionGoalsCalculator(stateMachine, roleOrdering.roleIndexToRole(0));

      for(Role role : xiStateMachine.getRoles())
      {
        ForwardDeadReckonInternalMachineState roleMask = xiStateMachine.createEmptyInternalState();

        for(PolymorphicProposition p : roleSets.get(role))
        {
          roleMask.add(((ForwardDeadReckonProposition)p).getInfo());
        }

        propGoalsCalculator.addRoleMask(role, roleMask);
      }

      goalsCalculator = propGoalsCalculator;
    }

    tuningComplete = (goalsCalculator == null);

    return tuningComplete;
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
    if ( predictionsMatch )
    {
      for (int i = 0; i < xiRoleScores.length; i++)
      {
        int predictedScore = goalsCalculator.getGoalValue(xiState, roleOrdering.roleIndexToRole(i));

        switch(predictedScore)
        {
          case 0:
            if ( xiRoleScores[i] == 0 )
            {
              if ( roleReversalFixed )
              {
                if ( reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
              }
            }
            else if ( xiRoleScores[i] == 100 )
            {
              if ( roleReversalFixed )
              {
                if ( !reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
                reverseRoles = true;
              }
            }
            else
            {
              predictionsMatch = false;
            }
            break;
          case 50:
            if ( xiRoleScores[i] != 50 )
            {
              predictionsMatch = false;
            }
            break;
          case 100:
            if ( xiRoleScores[i] == 100 )
            {
              if ( roleReversalFixed )
              {
                if ( reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
              }
            }
            else if ( xiRoleScores[i] == 0 )
            {
              if ( roleReversalFixed )
              {
                if ( !reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
                reverseRoles = true;
              }
            }
            else
            {
              predictionsMatch = false;
            }
            break;
          default:
            assert(false);
            break;
        }
      }
    }
  }

  @Override
  public void tuningComplete()
  {
    tuningComplete = true;

    if ( predictionsMatch )
    {
      goalsCalculator.endLearning();

      if ( reverseRoles )
      {
        goalsCalculator.reverseRoles();
      }
      stateMachine.setGoalsCalculator(goalsCalculator);

      LOGGER.info("Using emulated goals calculator (majority: " + goalsCalculator.getName() + ")");
    }
    else
    {
      goalsCalculator = null;
    }
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState,
                      TreeNode xiNode)
  {
    //  If there is a derived heuristic delegate to it
    if ( derivedHeuristic != null )
    {
      derivedHeuristic.newTurn(xiState, xiNode);
    }
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
    //  If there is a derived heuristic delegate to it
    if ( derivedHeuristic != null )
    {
      derivedHeuristic.getHeuristicValue(xiState, xiPreviousState, xiReferenceState, resultInfo);
    }
    else
    {
      resultInfo.treatAsSequenceStep = false;
      resultInfo.heuristicWeight = 0;
    }
  }

  @Override
  public boolean isEnabled()
  {
    if ( !tuningComplete )
    {
      return true;
    }

    if ( goalsCalculator != null)
    {
      derivedHeuristic = goalsCalculator.getDerivedHeuristic();
    }

    return (goalsCalculator != null && derivedHeuristic != null);
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    return this;
  }

  /**
   * Calculate the set of base propositions which a specified component is dependent on without passing through
   * any transitions.  Not that does<->legal IS traversed
   * @param pn - propnet
   * @param c - component whose immediate base dependencies are to be calculated
   * @param baseProps - set of base props found
   * @param processedComponents - set of components already visited durign the recursion
   */
  public static void recursiveFindFeedingBaseProps(PolymorphicPropNet pn, PolymorphicComponent c, Set<PolymorphicProposition> baseProps, Set<PolymorphicComponent> processedComponents)
  {
    if ( processedComponents.contains(c))
    {
      return;
    }

    processedComponents.add(c);

    if ( c instanceof PolymorphicProposition )
    {
      if ( pn.getBasePropositions().values().contains(c))
      {
        baseProps.add((PolymorphicProposition)c);
      }
      else
      {
        //  Allow does->legal crossing
        PolymorphicProposition legal = pn.getLegalInputMap().get(c);
        if ( legal != null )
        {
          c = legal;
        }
      }
    }

    if ( c instanceof PolymorphicTransition )
    {
      return;
    }

    for(PolymorphicComponent input : c.getInputs())
    {
      recursiveFindFeedingBaseProps(pn, input, baseProps, processedComponents);
    }
  }

  @Override
  public boolean applyAsSimpleHeuristic()
  {
    return false;
  }
}
