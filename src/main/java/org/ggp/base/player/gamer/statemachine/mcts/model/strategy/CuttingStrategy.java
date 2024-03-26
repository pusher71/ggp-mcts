package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTree;
import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTreeNode;

public class CuttingStrategy {

    public void execute(SearchTree tree, SearchTreeNode startRootNode) {
        tree.setRoot(startRootNode);
    }
}
