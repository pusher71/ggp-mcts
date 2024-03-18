package org.ggp.base.player.gamer.statemachine.sancho;

import java.text.DecimalFormat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

/**
 * Information about the best choice to make from a factor.
 */
public class FactorMoveChoiceInfo
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final DecimalFormat FORMAT_2DP = new DecimalFormat("####0.00");

  /**
   * The best move in this factor.
   */
  public ForwardDeadReckonLegalMoveInfo        mBestMove;

  /**
   * The value of playing the best move in this factor.
   */
  public double                                mBestMoveValue;

  /**
   * Whether the best move takes us to a complete node (i.e. one where the score is known for sure).
   */
  public boolean                               mBestMoveIsComplete;

  /**
   * The tree edge representing the best move in this factor.
   */
  public TreeEdge                              mBestEdge;

  /**
   * The game state that results from the playing the best move.
   */
  public ForwardDeadReckonInternalMachineState mResultingState;

  /**
   * The value of not playing in this factor.
   */
  public double                                mPseudoNoopValue;

  /**
   * Whether not playing in this factor takes us to a complete node (i.e. one where the score is known for sure).
   */
  public boolean                               mPseudoMoveIsComplete;

  /**
   * Trace of the expected path through this factor.  Suitable for logging.
   */
  public String mPathTrace;

  /**
   * Log full debugging information about the factor move choice.
   */
  public void logFull()
  {
    LOGGER.debug("  Factor best move:          " + (mBestMove.mIsPseudoNoOp ? null : mBestMove.mMove));
    LOGGER.debug("  Factor best move value:    " + mBestMoveValue);
    LOGGER.debug("  Factor best move complete? " + mBestMoveIsComplete);
    LOGGER.debug("  Factor no-op value:        " + mPseudoNoopValue);
    LOGGER.debug("  Factor no-op complete?     " + mPseudoMoveIsComplete);
  }

  /**
   * Log brief information about the best choice.
   */
  public void logBest()
  {
    if (mBestMove == null)
    {
      // No move in this factor this turn.
      return;
    }

    LOGGER.info("Best move was " + (mBestMove.mIsPseudoNoOp ? "<Pseudo no-op>" : mBestMove.mMove) +
                ", scoring " + FORMAT_2DP.format(mBestMoveValue));
  }
}
