package org.ggp.base.util.statemachine.playoutPolicy;

import java.util.Random;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.StateMachineFilter;

public class PlayoutPolicyCriticalResponse implements IPlayoutPolicy
{
  private final double EPSILON_GREEDY_THRESHOLD = 0.8;

  private final PlayoutPolicyCriticalResponse mMaster;
  private int[] mResponseMap = null;
  private int   mPreviousMoveMasterIndex;
  private ForwardDeadReckonLegalMoveSet mLegalMoves = null;
  final private Random  rand = new Random();
  final private ForwardDeadReckonPropnetStateMachine mStateMachine;

  public PlayoutPolicyCriticalResponse(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    mMaster = this;
    mStateMachine = xiStateMachine;
  }

  private PlayoutPolicyCriticalResponse(PlayoutPolicyCriticalResponse master, ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    mMaster = master;
    mStateMachine = xiStateMachine;
  }

  public void setReponseMap(int[] responseMap)
  {
    mResponseMap = responseMap;
  }

  @Override
  public IPlayoutPolicy cloneFor(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    return new PlayoutPolicyCriticalResponse(this, xiStateMachine);
  }

  @Override
  public void noteCurrentState(ForwardDeadReckonInternalMachineState xiState,
                               ForwardDeadReckonLegalMoveSet xiLegalMoves,
                               StateMachineFilter xiFactor,
                               int xiMoveIndex,
                               ForwardDeadReckonLegalMoveInfo[] xiMoveHistory,
                               ForwardDeadReckonInternalMachineState[] xiStateHistory)
  {
    mLegalMoves = xiLegalMoves;
    if ( xiMoveIndex == 0 || mMaster.mResponseMap == null )
    {
      mPreviousMoveMasterIndex = -1;
    }
    else
    {
      mPreviousMoveMasterIndex = xiMoveHistory[xiMoveIndex-1].mMasterIndex;
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
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean terminatePlayout()
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void noteNewTurn()
  {
    // TODO Auto-generated method stub

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
    // TODO Auto-generated method stub

  }

  @Override
  public ForwardDeadReckonLegalMoveInfo selectMove(int xiRoleIndex)
  {
    if ( mPreviousMoveMasterIndex == -1 || rand.nextDouble() > EPSILON_GREEDY_THRESHOLD )
    {
      return null;
    }

    int prevIndex = mMaster.mResponseMap[mPreviousMoveMasterIndex];
    if ( prevIndex != -1 )
    {
      ForwardDeadReckonLegalMoveInfo response = mLegalMoves.getMasterList()[prevIndex];
      if ( mLegalMoves.getContents(xiRoleIndex).contains(response))
      {
        return response;
      }
    }

    return null;
  }

  @Override
  public boolean isAcceptableMove(ForwardDeadReckonLegalMoveInfo xiCandidate,
                                  int xiRoleIndex)
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean isAcceptableState(ForwardDeadReckonInternalMachineState xiToState,
                                   int xiRoleIndex)
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean popStackOnAllUnacceptableMoves(int xiPopDepth)
  {
    // TODO Auto-generated method stub
    return false;
  }

}
