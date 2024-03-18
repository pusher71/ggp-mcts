package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * @author steve
 * Abstract base class for emulated goal calculators whose result is based on comparison
 * of numeric scores, with the score being (0,100), (50,50), or (100,0) depednign on the sign
 * of the difference in scores
 */
public abstract class MajorityCalculator implements ReversableGoalsCalculator
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * Underlying state machine
   */
  protected final ForwardDeadReckonPropnetStateMachine stateMachine;
  private boolean                                      learningMode = true;
  /**
   * Whether the (terminal) counts always add up to a fixed total
   */
  protected boolean                                    isFixedSum = true;
  /**
   * The fixed total that terminal counts always add up tro (if fixed sum)
   */
  protected int                                        valueTotal = -1;
  /**
   * Our role in the game
   */
  protected final Role                                 ourRole;

  /**
   * @param xiStateMachine - underlying state machine
   * @param xiOurRole - our role in the game
   */
  protected MajorityCalculator(ForwardDeadReckonPropnetStateMachine xiStateMachine, Role xiOurRole)
  {
    stateMachine = xiStateMachine;
    ourRole = xiOurRole;
  }

  /**
   * Turn off learning mode (after which the goal calculator will be considered definitive if still valid at this point)
   */
  public void endLearning()
  {
    learningMode = false;

    if ( isFixedSum )
    {
      LOGGER.info("Terminal goals are fixed sum with total value: " + valueTotal);
    }
  }

  /**
   * @param xiState - state to get count value in
   * @param role - role for which the count is to be evaluated
   * @return count for the specified role in th specified state
   */
  protected abstract int getCount(ForwardDeadReckonInternalMachineState xiState,
                                  Role role);

  @Override
  public int getGoalValue(ForwardDeadReckonInternalMachineState xiState,
                          Role xiRole)
  {
    int ourValue = 0;
    int theirValue = 0;

    for(Role role : stateMachine.getRoles())
    {
      int value = getCount(xiState, role);

      if ( role.equals(xiRole))
      {
        ourValue = value;

        if ( !learningMode && isFixedSum )
        {
          theirValue = valueTotal - ourValue;
          break;
        }
      }
      else
      {
        theirValue = value;

        if ( !learningMode && isFixedSum )
        {
          ourValue = valueTotal - theirValue;
          break;
        }
      }
    }

    if ( learningMode && isFixedSum )
    {
      int total = ourValue + theirValue;

      if ( valueTotal == -1 )
      {
        valueTotal = total;
      }
      else if ( total != valueTotal )
      {
        learningMode = false;
        isFixedSum = false;
      }
    }

    if ( ourValue > theirValue )
    {
      return 100;
    }
    if ( ourValue < theirValue )
    {
      return 0;
    }
    return 50;
  }

  /**
   * If the goals calculation supports a derived heuristic obtain
   * an instance of that heuristic
   * @return instance of the derived heuristic
   */
  public abstract Heuristic getDerivedHeuristic();
}
