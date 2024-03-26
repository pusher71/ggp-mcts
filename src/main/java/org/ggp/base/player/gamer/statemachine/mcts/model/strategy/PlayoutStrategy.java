package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTreeNode;
import org.ggp.base.util.statemachine.Role;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayoutStrategy {

    // Возвращаемое значение - полученные очки для каждой роли
    public Map<Role, Double> execute(SearchTreeNode startNode) {
        Map<Role, Double> scores = new HashMap<>();
        List<Role> roles = startNode.getGameModel().getRoles();
        for (int i = 0; i < roles.size(); i++) {
            scores.put(roles.get(i), (double) i); //TODO пока просто i
        }
        return scores;
        //TODO реализовать иммитацию дальнейших ходов
    }
}
