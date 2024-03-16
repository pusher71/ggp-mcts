package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Move;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class RoleActionStatistics {

    private Map<Move, Item> map; // Очки, привязанные к действиям, и их количества использований

    public RoleActionStatistics() {
        map = new HashMap<>();
    }

    // Записать очки для данного действия
    public void addActionScore(Move action, int score) {
        map.put(action, new Item(score));
    }

    // инкрементировать количество использований данного действия
    public void incActionNumUses(Move action) {
        if (!map.containsKey(action)) {
            map.put(action, new Item(0, 1));
        } else {
            Item oldValue = map.get(action);
            oldValue.actionNumUses++;
        }
    }

    public Set<Move> getUsedActions() {
        return map.keySet();
    }

    class Item {
        private int actionScore;
        private int actionNumUses;

        public Item(int score) {
            actionScore = score;
            actionNumUses = 0;
        }

        public Item(int score, int numUses) {
            actionScore = score;
            actionNumUses = numUses;
        }
    }
}
