package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;

/**
 * Dummy latch results for players that don't do analysis.
 */
public class DummyLatchResults implements LatchResults
{
  @Override
  public boolean isComplete()
  {
    return false;
  }

  @Override
  public boolean hasPositivelyLatchedGoals()
  {
    return false;
  }

  @Override
  public boolean hasNegativelyLatchedGoals()
  {
    return false;
  }

  @Override
  public boolean isPositivelyLatchedBaseProp(PolymorphicProposition xiProposition)
  {
    return false;
  }

  @Override
  public boolean isNegativelyLatchedBaseProp(PolymorphicProposition xiProposition)
  {
    return false;
  }

  @Override
  public ForwardDeadReckonInternalMachineState getPositiveBaseLatches()
  {
    return null;
  }

  @Override
  public long getNumPositiveBaseLatches()
  {
    return 0;
  }

  @Override
  public long getNumNegativeBaseLatches()
  {
    return 0;
  }

  @Override
  public boolean scoresAreLatched(ForwardDeadReckonInternalMachineState xiState)
  {
    return false;
  }

  @Override
  public void getLatchedScoreRange(ForwardDeadReckonInternalMachineState xiState,
                                   Role xiRole,
                                   PolymorphicProposition[] xiGoals,
                                   int[] xoRange)
  {
    xoRange[0] = 0;
    xoRange[1] = 100;
  }

  @Override
  public void report()
  {
    // Nothing to report for dummy latches.
  }
}
