package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

import org.ggp.base.player.gamer.statemachine.mcts.model.RoleActionStatistics;
import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTreeNode;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

public class SelectionStrategyForMatch {

    public Move execute(SearchTreeNode node, Role choosingRole) {
        double bestActionScore = 0;
        Move bestAction = null;
        RoleActionStatistics roleActionStatistics = node.getStatistics().getRoleActionStatistics(choosingRole);
        for (Move action : roleActionStatistics.getUseActions()) {
            RoleActionStatistics.Item item = roleActionStatistics.get(action);
            int actionNumUsed = item.getActionNumUsed();
            int stateNumVisits = node.getStatistics().getNumVisits();
            double actionScore = 999; //TODO выбрать вариант расчёта
            if (actionScore > bestActionScore) {
                bestActionScore = actionScore;
                bestAction = action;
            }
        }

        return bestAction;
    }
}
