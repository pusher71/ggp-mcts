package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

import java.util.HashMap;
import java.util.Map;

public class MonteCarloTreeSearch {

    private GameModel model;
    private Map<String, Integer> searchBudget;

    public MonteCarloTreeSearch(GameModel model) {
        this.model = model;
        searchBudget = new HashMap<>();
    }

    public Move execute(State startState, Role choosingRole) {
        return null;
    }
}
