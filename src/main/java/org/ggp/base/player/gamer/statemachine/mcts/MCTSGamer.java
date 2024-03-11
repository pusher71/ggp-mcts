package org.ggp.base.player.gamer.statemachine.mcts;

import org.ggp.base.player.gamer.statemachine.mcts.model.GameModel;
import org.ggp.base.player.gamer.statemachine.mcts.model.State;
import org.ggp.base.player.gamer.statemachine.sample.SampleGamer;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;

import java.util.HashMap;
import java.util.Map;

public class MCTSGamer extends SampleGamer {

    private GameModel model;
    private Map<String, Integer> searchBudget;

    public MCTSGamer(GameModel model) {
        this.model = model;
        searchBudget = new HashMap<>();
    }

    public Move execute(State startState, Role choosingRole) {
        return null;
    }

    @Override
    public Move stateMachineSelectMove(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {
        return null;
    }
}
