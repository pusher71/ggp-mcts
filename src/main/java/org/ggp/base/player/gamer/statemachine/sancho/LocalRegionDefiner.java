package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;


/**
 * @author steve
 * Interface that allows querying of whether a move is included in the interface
 * implementor's local search region
 */
public interface LocalRegionDefiner
{
  /**
   * @param move
   * @return whether the move is part of the local region
   */
  boolean isLocal(ForwardDeadReckonLegalMoveInfo move);

  /**
   * If the region includes a found win path whether a given move from the start
   * state could influence that win
   * @param move Move to check for possible influence
   * @return true if the move could potentially disturb the found win
   */
  boolean canInfluenceFoundResult(ForwardDeadReckonLegalMoveInfo move);

  /**
   * @return true if the region's seed itself may influence the found result
   */
  boolean seedMayEnableResult();

  /**
   * @param move
   * @return minimum possible move count before provided move could cause a win
   * for the player playing it
   */
  int getMinWinDistance(ForwardDeadReckonLegalMoveInfo move);

  /**
   * @return true if we have lower bounds on win distances from moves for this game
   */
  boolean hasKnownWinDistances();
}