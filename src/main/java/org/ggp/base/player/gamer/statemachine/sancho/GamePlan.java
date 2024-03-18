package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Move;

/**
 * @author steve
 * Abstracts the planned list of moves we want to make from the current state
 * to the end of the game.  Intended for use by puzzles to record full playout
 * paths once a solution is identified
 */
public class GamePlan
{
  private static final Logger LOGGER = LogManager.getLogger();

  private Queue<Move>                 plan                            = new LinkedList<>();
  private boolean                     enacting                        = false;

  /**
   * Get the size of the plan (in moves)
   * @return size
   */
  public int size()
  {
    return plan.size();
  }

  /**
   * Consider replacing the current plan by a provided candidate.
   * The shortest plan will be taken.  Note that this method is thread-safe
   * @param candidate new plan to consider
   */
  public void considerPlan(Collection<Move> candidate)
  {
    //  Never replace a plan we have already started enacting!
    if ( !enacting )
    {
      synchronized(this)
      {
        if ( isEmpty() || candidate.size() < plan.size() )
        {
          plan.clear();
          plan.addAll(candidate);
        }
      }
    }
  }

  /**
   * Consider replacing the current plan by a provided candidate.
   * The shortest plan will be taken.  Note that this method is thread-safe
   * @param candidate new plan to consider
   */
  public void considerPlan(List<ForwardDeadReckonLegalMoveInfo> candidate)
  {
    //  Never replace a plan we have already started enacting!
    if ( !enacting )
    {
      synchronized(this)
      {
        if ( isEmpty() || candidate.size() < plan.size() )
        {
          plan.clear();
          for(ForwardDeadReckonLegalMoveInfo move : candidate)
          {
            plan.add(move.mMove);
          }

          LOGGER.info("Cached new best plan: " + plan);
        }
      }
    }
  }

  /**
   * Is the current plan empty (i.e. - contains no moves)
   * @return true if it is empty
   */
  public boolean isEmpty()
  {
    return plan.isEmpty();
  }

  /**
   * Get the next move in the plan (destructively).  Caller is
   * expected to first check the plan is not empty
   * @return next move
   */
  public Move nextMove()
  {
    assert(!isEmpty());

    enacting = true;
    return plan.remove();
  }

  @Override
  public String toString()
  {
    return plan.toString();
  }
}
