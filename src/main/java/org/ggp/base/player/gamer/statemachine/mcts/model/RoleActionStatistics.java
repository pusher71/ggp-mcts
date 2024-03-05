package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Move;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class RoleActionStatistics {

    private Map<Move, Integer> actionScores; // Очки, привязанные к действиям
    private Map<Move, Integer> actionNumUses; // Количество использований каждого действия

    public RoleActionStatistics() {
        actionScores = new HashMap<>();
        actionNumUses = new HashMap<>();
    }

    // Записать очки для данного действия
    public void addActionScore(Move action, int score) {
        actionScores.put(action, score);
    }

    // Инкрементировать количество использований данного действия
    public void incActionNumUses(Move action) {
        if (!actionNumUses.containsKey(action)) {
            actionNumUses.put(action, 1);
        } else {
            int oldValue = actionNumUses.get(action);
            actionNumUses.put(action, oldValue + 1);
        }
    }

    public Set<Move> getUsedActions() {
        return actionNumUses.keySet();
    }
}
