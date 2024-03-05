package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Role;

import java.util.List;

public interface GameModel {
    State getStartState();
    State getNextState(State state);
    List<Role> getRoles();
    List<JointActions> getJointMoves(State state);
}
