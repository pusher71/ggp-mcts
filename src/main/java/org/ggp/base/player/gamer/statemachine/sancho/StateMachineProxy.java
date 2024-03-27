package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.List;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;

interface ActivityController
{
  public void requestYield(boolean state);
  public Object getSerializationObject();
}

class StateMachineProxy extends StateMachine
{
  private final StateMachine machineToProxy;
  private ActivityController controller = null;

  public StateMachineProxy(StateMachine proxyTo)
  {
    machineToProxy = proxyTo;
  }

  public void setController(ActivityController controller)
  {
    this.controller = controller;
  }

  @Override
  public void initialize(List<Gdl> description)
  {
    machineToProxy.initialize(description);
  }

  @Override
  public int getGoal(MachineState state, Role role)
      throws GoalDefinitionException
  {
    synchronized (controller.getSerializationObject())
    {
      return machineToProxy.getGoal(state, role);
    }
  }

  @Override
  public boolean isTerminal(MachineState state)
  {
    synchronized (controller.getSerializationObject())
    {
      return machineToProxy.isTerminal(state);
    }
  }

  @Override
  public List<Role> getRoles()
  {
    return machineToProxy.getRoles();
  }

  @Override
  public MachineState getInitialState()
  {
    return machineToProxy.getInitialState();
  }

  @Override
  public List<Move> getLegalMoves(MachineState state, Role role)
      throws MoveDefinitionException
  {
    synchronized (controller.getSerializationObject())
    {
      return machineToProxy.getLegalMoves(state, role);
    }
  }

  @Override
  public MachineState getNextState(MachineState state, List<Move> moves)
  {
    MachineState result;

    if ( controller != null )
    {
      controller.requestYield(true);
    }
    synchronized (controller.getSerializationObject())
    {
      result = machineToProxy.getNextState(state, moves);
    }
    if ( controller != null )
    {
      controller.requestYield(false);
    }

    return result;
  }
}