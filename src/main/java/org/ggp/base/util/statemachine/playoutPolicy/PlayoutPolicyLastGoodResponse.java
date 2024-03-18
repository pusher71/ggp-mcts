package org.ggp.base.util.statemachine.playoutPolicy;

import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.StateMachineFilter;

/**
 * @author steve
 *  Playout policy implementing basic last good response
 */
public class PlayoutPolicyLastGoodResponse implements IPlayoutPolicy
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final double EPSILON_GREEDY_THRESHOLD = 0.7;
  private final boolean traceStats = true;

  private final ForwardDeadReckonPropnetStateMachine stateMachine;
  private final float[][]  bestResponseScores;
  private final float[][]  responseSampleSize;
  private final int[] latchedScoreRangeBuffer = new int[2];
  private final int[] opponentEquivalent;

  private ForwardDeadReckonLegalMoveInfo[] playoutMoves = null;
  private int                              currentMoveIndex;
  private final int                        ourRoleIndex;
  private final Role                       ourRole;
  final private Random                     rand = new Random();

  private ForwardDeadReckonLegalMoveSet    availableMoves = null;

  /**
   * Construct a new instance
   * @param xiStateMachine
   */
  public PlayoutPolicyLastGoodResponse(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    stateMachine = xiStateMachine;
    bestResponseScores = new float[stateMachine.getFullPropNet().getMasterMoveList().length][stateMachine.getFullPropNet().getMasterMoveList().length];
    responseSampleSize = new float[stateMachine.getFullPropNet().getMasterMoveList().length][stateMachine.getFullPropNet().getMasterMoveList().length];
    opponentEquivalent = new int[stateMachine.getFullPropNet().getMasterMoveList().length];

    for(int i = 0; i < stateMachine.getFullPropNet().getMasterMoveList().length; i++)
    {
      opponentEquivalent[i] = -1;
      for(int j = 0; j < stateMachine.getFullPropNet().getMasterMoveList().length; j++)
      {
        if (i != j &&
            stateMachine.getFullPropNet().getMasterMoveList()[i] != null &&
            stateMachine.getFullPropNet().getMasterMoveList()[j] != null &&
            stateMachine.getFullPropNet().getMasterMoveList()[i].mMove.equals(stateMachine.getFullPropNet().getMasterMoveList()[j].mMove))
        {
          opponentEquivalent[i] = j;
          break;
        }
      }
    }

    ourRoleIndex = stateMachine.getRoleOrdering().getOurRawRoleIndex();
    ourRole = stateMachine.getRoleOrdering().getOurRole();
  }

  @Override
  public IPlayoutPolicy cloneFor(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    return new PlayoutPolicyLastGoodResponse(xiStateMachine);
  }

  @Override
  public void noteCurrentState(ForwardDeadReckonInternalMachineState xiState,
                               ForwardDeadReckonLegalMoveSet xiLegalMoves,
                               StateMachineFilter xiFactor,
                               int xiMoveIndex,
                               ForwardDeadReckonLegalMoveInfo[] xiMoveHistory,
                               ForwardDeadReckonInternalMachineState[] xiStateHistory)
  {
    playoutMoves = xiMoveHistory;
    currentMoveIndex = xiMoveIndex;
    availableMoves = xiLegalMoves;

    if ( currentMoveIndex == 0 )
    {
      stateMachine.getLatchedScoreRange(xiState, ourRole, latchedScoreRangeBuffer);
    }
  }

  @Override
  public boolean requiresMoveHistory()
  {
    return true;
  }

  @Override
  public boolean requiresStateHistory()
  {
    return false;
  }

  @Override
  public boolean terminatePlayout()
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void noteNewPlayout()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void noteCompletePlayout(int xiLength,
                                  ForwardDeadReckonLegalMoveInfo[] xiMoves,
                                  ForwardDeadReckonInternalMachineState[] xiStates)
  {
    if ( xiMoves != null )
    {
      ForwardDeadReckonLegalMoveInfo prev = null;
      ForwardDeadReckonLegalMoveInfo prevPrev = null;
      int ourGoalValue = stateMachine.getGoal(ourRole);
      boolean isWin = (ourGoalValue == latchedScoreRangeBuffer[1]);

      for(int i = 0; i < xiLength; i++)
      {
        ForwardDeadReckonLegalMoveInfo move = xiMoves[i];
        if ( prev != null )
        {
          responseSampleSize[prev.mMasterIndex][move.mMasterIndex]++;
          if ( (move.mRoleIndex == ourRoleIndex) == isWin )
          {
            bestResponseScores[prev.mMasterIndex][move.mMasterIndex]++;
          }
        }

        if ( prevPrev != null )
        {
          responseSampleSize[prevPrev.mMasterIndex][move.mMasterIndex]++;
          if ( (move.mRoleIndex == ourRoleIndex) == isWin )
          {
            bestResponseScores[prevPrev.mMasterIndex][move.mMasterIndex]++;
          }
        }

        prevPrev = prev;
        prev = move;
      }
    }
  }

  @Override
  public ForwardDeadReckonLegalMoveInfo selectMove(int xiRoleIndex)
  {
    if ( currentMoveIndex == 0 || rand.nextDouble() > EPSILON_GREEDY_THRESHOLD )
    {
      return null;
    }

    ForwardDeadReckonLegalMoveInfo bestResponse = null;
    float[] moveResponseScores = bestResponseScores[playoutMoves[currentMoveIndex-1].mMasterIndex];
    float[] moveResponseSamples = responseSampleSize[playoutMoves[currentMoveIndex-1].mMasterIndex];
    float[] moveFollowOnScores = (currentMoveIndex > 1 ? bestResponseScores[playoutMoves[currentMoveIndex-2].mMasterIndex] : null);
    float[] moveFollowOnSamples = (currentMoveIndex > 1 ? responseSampleSize[playoutMoves[currentMoveIndex-2].mMasterIndex] : null);
    float best = -Float.MAX_VALUE;
    for(int i = 0; i < moveResponseScores.length; i++)
    {
      ForwardDeadReckonLegalMoveInfo response = stateMachine.getFullPropNet().getMasterMoveList()[i];

      if ( response.mRoleIndex == xiRoleIndex )
      {
        float score = 1;
        boolean valid = false;
        if ( moveFollowOnSamples != null && moveFollowOnSamples[i] > 0 )
        {
          assert(moveFollowOnScores != null);
          score += moveFollowOnScores[i]/moveFollowOnSamples[i];
          valid = true;
        }
        if ( moveResponseSamples[i] > 0 )
        {
          score *= moveResponseScores[i]/moveResponseSamples[i];
          valid = true;
        }
        if ( valid && score > best )
        {
          if ( availableMoves.getContents(xiRoleIndex).contains(response) )
          {
            bestResponse = availableMoves.getMasterList()[response.mMasterIndex];
            best = score;
          }
        }
      }
    }

//    if ( bestResponse != null )
//    {
//      LOGGER.info("Selected move: " + bestResponse.inputProposition + " for role " + xiRoleIndex);
//    }
//    else
//    {
//      LOGGER.info("No selection made for role " + xiRoleIndex);
//    }
    return bestResponse;
  }

  @Override
  public boolean isAcceptableMove(ForwardDeadReckonLegalMoveInfo xiCandidate,
                                  int xiRoleIndex)
  {
    return true;
  }

  @Override
  public boolean isAcceptableState(ForwardDeadReckonInternalMachineState xiToState,
                                   int xiRoleIndex)
  {
    return true;
  }

  @Override
  public boolean popStackOnAllUnacceptableMoves(int xiPopDepth)
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void noteNewTurn()
  {
    //  Dump best responses from the previous turn for first non-master instance
    if ( traceStats && stateMachine.getInstanceId() == 1 )
    {
      ForwardDeadReckonLegalMoveInfo[] masterList = stateMachine.getFullPropNet().getMasterMoveList();
      for(int i = 0; i < bestResponseScores.length; i++)
      {
        float best = -Float.MAX_VALUE;
        float bestFollow = -Float.MAX_VALUE;
        int bestIndex = -1;
        int bestFollowIndex = -1;
        for(int j = 0; j < bestResponseScores.length; j++)
        {
          if ( responseSampleSize[i][j] > 0 )
          {
            float score = bestResponseScores[i][j]/responseSampleSize[i][j];
            if ( masterList[i].mRoleIndex != masterList[j].mRoleIndex)
            {
              if ( score > best )
              {
                best = score;
                bestIndex = j;
              }
            }
            else
            {
              if ( score > bestFollow )
              {
                bestFollow = score;
                bestFollowIndex = j;
              }
            }
          }
        }

        LOGGER.info("Best response to " + masterList[i].mInputProposition + ": " + (bestIndex == -1 ? "NONE" : masterList[bestIndex].mInputProposition + " (" + (100*best) + "% [" + responseSampleSize[i][bestIndex] + "] )"));
        LOGGER.info("Best follow-on to " + masterList[i].mInputProposition + ": " + (bestFollowIndex == -1 ? "NONE" : masterList[bestFollowIndex].mInputProposition + " (" + (100*bestFollow) + "% [" + responseSampleSize[i][bestFollowIndex] + "] )"));
        if ( bestIndex != -1 && opponentEquivalent[bestIndex] != bestFollowIndex )
        {
          int bestDeny = opponentEquivalent[bestIndex];
          LOGGER.info("Best denial to " + masterList[i].mInputProposition + ": " + (bestDeny == -1 ? "NONE" : masterList[bestDeny].mInputProposition + " (" + (100*(bestResponseScores[i][bestDeny]/responseSampleSize[i][bestDeny])) + "% [" + responseSampleSize[i][bestDeny] + "] )"));
        }
      }
    }

    // Reset the stats
    for(int i = 0; i < bestResponseScores.length; i++)
    {
      for(int j = 0; j < bestResponseScores.length; j++)
      {
        bestResponseScores[i][j] /= 2;
        responseSampleSize[i][j] /= 2;
      }
    }
  }
}
