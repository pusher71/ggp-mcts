package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

import org.ggp.base.player.gamer.statemachine.mcts.model.CumulativeStatistics;
import org.ggp.base.player.gamer.statemachine.mcts.model.JointActions;
import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTreeNode;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

public class SelectionStrategy {

    public SearchTreeNode execute(SearchTreeNode startNode) {
        return null;
    }

    public JointActions getJointBestActions(SearchTreeNode node) {
        return null;
    }

    private SearchTreeNode getBestAction(CumulativeStatistics statistics, Role role) {
        return null;
    }

    private double getExploitationScore(CumulativeStatistics statistics, Role role, Move action) {
        return 0;
    }

    private double getExplorationScore(CumulativeStatistics statistics, Role role, Move action) {
        return 0;
    }

    private boolean isNodeVisited(SearchTreeNode node) {
        return false;
    }

    public void backPropagation(SearchTreeNode node) {

    }

    public void updateStatistic(SearchTreeNode node, int mode) { //TODO mode - enum

    }
}
