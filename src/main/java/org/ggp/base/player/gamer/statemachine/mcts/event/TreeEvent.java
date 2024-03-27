package org.ggp.base.player.gamer.statemachine.mcts.event;

import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTree;
import org.ggp.base.util.observer.Event;

public class TreeEvent extends Event {

    private final SearchTree tree;
    private final int turnNumber;

    public TreeEvent(SearchTree tree, int turnNumber) {
        this.tree = tree;
        this.turnNumber = turnNumber;
    }

    public SearchTree getTree() {
        return tree;
    }

    public int getTurnNumber() {
        return turnNumber;
    }
}
