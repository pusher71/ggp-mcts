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

    public Item get(Move action) {
        return map.get(action);
    }

    public void putAction(Move action) {
        map.put(action, new Item());
    }

    public void inc(Move action, double actionScore) {
        Item item = map.get(action);
        item.actionScore += actionScore;
        item.actionNumUsed++;
    }

    public Set<Move> getUseActions() {
        return map.keySet();
    }

    public class Item {
        private double actionScore;
        private int actionNumUsed;

        public Item() {
            actionScore = 0;
            actionNumUsed = 0;
        }

        public double getActionScore() {
            return actionScore;
        }

        public int getActionNumUsed() {
            return actionNumUsed;
        }
    }
}
