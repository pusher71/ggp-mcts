package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

import org.ggp.base.player.gamer.statemachine.mcts.model.JointActions;
import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTreeNode;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class ExpansionStrategy {

    public SearchTreeNode execute(SearchTreeNode node) throws MoveDefinitionException {
        if (isNodeNeedExpanded(node)) {
            return node;
        }

        List<List<Move>> jointMoves = node.getGameModel().getLegalJointMoves(node.getState());
        int childRandomIndex = ThreadLocalRandom.current().nextInt(jointMoves.size());
        SearchTreeNode childRandom = null;
        for (int i = 0; i < jointMoves.size(); i++) {
            SearchTreeNode child = node.createChild(new JointActions(jointMoves.get(i), node.getGameModel().getRoles()));
            if (i == childRandomIndex) {
                childRandom = child;
            }
        }

        return childRandom;
    }

    private boolean isNodeNeedExpanded(SearchTreeNode node) {
        return node.isRoot() || node.getStatistics().getNumVisits() > 0;
    }
}
