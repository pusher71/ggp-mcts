package org.ggp.base.player.gamer.statemachine.mcts.model;

import java.util.HashSet;
import java.util.Set;

public class SearchTreeNode {

    private SearchTree treeOwner;
    private SearchTreeNode parent;
    private Set<SearchTreeNode> children;

    private CumulativeStatistics statistics;
    private State state;
    private JointActions precedingJointMove;

    public SearchTreeNode(SearchTree treeOwner) {
        this.treeOwner = treeOwner;
        children = new HashSet<>();
    }

    public CumulativeStatistics getStatistics() {
        return statistics;
    }

    public State getState() {
        return state;
    }

    public JointActions getPrecedingJointMove() {
        return precedingJointMove;
    }
}
