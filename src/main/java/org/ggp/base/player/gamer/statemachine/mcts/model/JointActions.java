package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Move;

import java.util.ArrayList;
import java.util.List;

public class JointActions {

    private List<Move> actions;

    public JointActions() {
        actions = new ArrayList<>();
    }

    public void addAction(Move action) {
        actions.add(action);
    }
}
