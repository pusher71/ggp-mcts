package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

public class SearchTree {

    private GameModel gameModel;
    private SearchTreeNode root;

    public SearchTree(GameModel gameModel) {
        this.gameModel = gameModel;
    }

    public SearchTreeNode findNode(State state) {
        return null;
    }

    public void cut(SearchTreeNode startNode) {

    }

    public void grow() {

    }

    public Move getBestAction(Role choosingRole) {
        return null;
    }
}
