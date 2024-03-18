package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.GoalsCalculator;

/**
 * @author steve
 * A goals calculator wherein it is not initially known if wins correspond to larger or lower scores
 * During tuning the sense may be reversed if necessary to attain the correct correlation
 */
public interface ReversableGoalsCalculator extends GoalsCalculator
{
  /**
   * Swap the masks between the (must be 2) roles
   */
  void reverseRoles();
}
