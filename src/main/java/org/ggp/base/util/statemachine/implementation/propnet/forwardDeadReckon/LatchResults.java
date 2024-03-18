
package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;


public interface LatchResults
{

  /**
   * @return whether latch analysis completed successfully.
   */
  public abstract boolean isComplete();

  /**
   * @return whether any positively latched goals have been identified.
   */
  public abstract boolean hasPositivelyLatchedGoals();

  /**
   * @return whether any negatively latched goals have been identified.
   */
  public abstract boolean hasNegativelyLatchedGoals();

  /**
   * @return whether the specified proposition is a positive latch.
   *
   * @param xiProposition - the proposition to test.
   */
  public abstract boolean isPositivelyLatchedBaseProp(PolymorphicProposition xiProposition);

  /**
   * @return whether the specified proposition is a negative latch.
   *
   * @param xiProposition - the proposition to test.
   */
  public abstract boolean isNegativelyLatchedBaseProp(PolymorphicProposition xiProposition);

  /**
   * @return a mask of all positively latched base props, or null if there are none.
   *
   * WARNING: Callers MUST NOT modify the returned mask.
   */
  public abstract ForwardDeadReckonInternalMachineState getPositiveBaseLatches();

  /**
   * @return the number of positively latched base props.
   */
  public abstract long getNumPositiveBaseLatches();

  /**
   * @return the number of negatively latched base props.
   */
  public abstract long getNumNegativeBaseLatches();

  /**
   * WARNING: Callers should almost always call ForwardDeadReckonPropnetStateMachine.scoresAreLatched instead
   *          (because it also handles emulated goals).
   *
   * @param xiState - state to test for latched score in
   * @return true if all roles' scores are latched
   */
  public abstract boolean scoresAreLatched(ForwardDeadReckonInternalMachineState xiState);

  /**
   * Get the latched range of possible scores for a given role in a given state
   *
   * WARNING: Callers should almost always call ForwardDeadReckonPropnetStateMachine.getLatchedScoreRange instead.
   *
   * @param xiState - the state
   * @param xiRole - the role
   * @param xiGoals - the goal propositions for the specified role
   * @param xoRange - array of length 2 to contain [min,max]
   */
  public abstract void getLatchedScoreRange(ForwardDeadReckonInternalMachineState xiState,
                                            Role xiRole,
                                            PolymorphicProposition[] xiGoals,
                                            int[] xoRange);

  /**
   * Log the latch analysis results.
   */
  public abstract void report();

}