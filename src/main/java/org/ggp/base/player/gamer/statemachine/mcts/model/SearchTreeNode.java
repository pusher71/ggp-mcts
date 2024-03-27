package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.player.gamer.statemachine.mcts.model.strategy.SelectionStrategy;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;

import java.util.HashSet;
import java.util.Set;

public class SearchTreeNode {

    private SearchTree treeOwner;

    private Role activeRole;
    private MachineState state;
    private JointActions precedingJointMove;

    private SearchTreeNode parent;
    private Set<SearchTreeNode> children;

    private CumulativeStatistics statistics;

    public SearchTreeNode(SearchTree treeOwner, Role activeRole, MachineState state, JointActions precedingJointMove) {
        this.treeOwner = treeOwner;
        this.activeRole = activeRole;
        this.state = state;
        this.precedingJointMove = precedingJointMove;
        children = new HashSet<>();
        statistics = new CumulativeStatistics(getGameModel().getRoles());
    }

    public StateMachine getGameModel() {
        return treeOwner.getGameModel();
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public boolean isRoot() {
        return parent == null;
    }

    public boolean isComplete() {
        return getGameModel().isTerminal(getState()); //TODO пока так, в mctsref определение посложнее
    }

    public SearchTreeNode findNode(MachineState state) {
        if (state.equals(getState())) {
            return this;
        } else {
            for (SearchTreeNode child : children) {
                SearchTreeNode finding = child.findNode(state);
                if (finding != null) {
                    return finding;
                }
            }
            return null;
        }
    }

    public SearchTreeNode getParent() {
        return parent;
    }

    public SearchTreeNode getChild(final JointActions jointMove) {
        return children.stream().filter(c -> c.precedingJointMove == jointMove).findFirst().orElse(null);
    }

    public SearchTreeNode createChild(JointActions usedJointMove) {
        MachineState nextState = getGameModel().getNextState(parent.getState(), usedJointMove.toList());
        SearchTreeNode childNode = new SearchTreeNode(treeOwner, getGameModel().getNextActiveRole(getActiveRole()), nextState, usedJointMove);
        linkChildToParent(this, childNode);
        treeOwner.getStrategies().getSelectionStrategy().updateStatistic(childNode, SelectionStrategy.Mode.MAKE_JOINT_MOVE, null);
        return childNode;
    }

    private static void linkChildToParent(SearchTreeNode parentNode, SearchTreeNode childNode) {
        childNode.parent = parentNode;
        parentNode.children.add(childNode);
    }

    public Move getBestAction(Role choosingRole) {
        return treeOwner.getStrategies().getSelectionStrategyForMatch().execute(this, choosingRole);
    }

    public CumulativeStatistics getStatistics() {
        return statistics;
    }

    public Role getActiveRole() {
        return activeRole;
    }

    public MachineState getState() {
        return state;
    }

    public JointActions getPrecedingJointMove() {
        return precedingJointMove;
    }
}
