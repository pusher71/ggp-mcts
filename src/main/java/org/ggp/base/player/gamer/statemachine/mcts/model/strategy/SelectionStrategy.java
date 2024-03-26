package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

import org.ggp.base.player.gamer.statemachine.mcts.model.CumulativeStatistics;
import org.ggp.base.player.gamer.statemachine.mcts.model.JointActions;
import org.ggp.base.player.gamer.statemachine.mcts.model.RoleActionStatistics;
import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTreeNode;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class SelectionStrategy {

    private static final double EXPLORATION_BIAS = 0.4;
    private static final double FIRST_PLAY_URGENCY = 10;

    public void execute(SearchTreeNode node, List<SearchTreeNode> result) {
        if (node.isLeaf()) {
            if (!node.isComplete()) {
                result.add(node);
            }
        } else {
            JointActions jointBestActions = getJointBestActions(node);
            SearchTreeNode selectedChild = node.getChild(jointBestActions);
            execute(selectedChild, result);
        }
    }

    public JointActions getJointBestActions(SearchTreeNode node) {
        JointActions jointBestActions = new JointActions();
        for (Role role : node.getGameModel().getRoles()) {
            Move bestAction = getBestAction(node.getStatistics(), role);
            jointBestActions.addAction(role, bestAction);
        }
        return jointBestActions;
    }

    private Move getBestAction(CumulativeStatistics statistics, Role role) {
        double bestActionScore = 0;
        Move bestAction = null;
        for (Move action : statistics.getRoleActionStatistics(role).getUseActions()) {
            double actionScore = getExplorationScore(statistics, role, action) +
                    getExploitationScore(statistics, role, action);
            if (actionScore > bestActionScore) {
                bestActionScore = actionScore;
                bestAction = action;
            }
        }

        return bestAction;
    }

    private double getExplorationScore(CumulativeStatistics statistics, Role role, Move action) {
        if (statistics.getNumVisits() == 0) {
            return FIRST_PLAY_URGENCY + ThreadLocalRandom.current().nextDouble(0, 1);
        } else {
            int actionNumUsed = statistics.getRoleActionStatistics(role).get(action).getActionNumUsed();
            int stateNumVisits = statistics.getNumVisits();
            return EXPLORATION_BIAS * Math.sqrt(2 * Math.log(stateNumVisits) / actionNumUsed);
        }
    }

    private double getExploitationScore(CumulativeStatistics statistics, Role role, Move action) {
        RoleActionStatistics.Item item = statistics.getRoleActionStatistics(role).get(action);
        double actionScore = item.getActionScore();
        int actionNumUsed = item.getActionNumUsed();
        return normalize(actionScore / actionNumUsed);
    }

    private static final double MIN_SCORE = 0;
    private static final double MAX_SCORE = 100;
    private double normalize(double score) {
        return (score - MIN_SCORE) / (MAX_SCORE - MIN_SCORE);
    }

    public void backPropagation(SearchTreeNode node, Map<Role, Double> playoutScore) {
        if (!node.isRoot()) {
            updateStatistic(node, Mode.BACK_PROPAGATION, playoutScore);
            backPropagation(node.getParent(), playoutScore);
        }
    }

    // actionScore используется только в режиме BACK_PROPAGATION
    public void updateStatistic(SearchTreeNode node, Mode mode, Map<Role, Double> playoutScore) {
        CumulativeStatistics statistics = node.getParent().getStatistics();
        JointActions ensuingJointMove = node.getPrecedingJointMove();

        for (Role role : node.getGameModel().getRoles()) {
            Move usedAction = ensuingJointMove.getActionByRole(role);
            RoleActionStatistics roleActionStatistics = statistics.getRoleActionStatistics(role);

            if (mode == Mode.MAKE_JOINT_MOVE) {
                roleActionStatistics.putAction(usedAction);
            } else if (mode == Mode.BACK_PROPAGATION) {
                roleActionStatistics.inc(usedAction, playoutScore.get(role));
            }
        }
    }

    public enum Mode {
        MAKE_JOINT_MOVE, BACK_PROPAGATION
    }
}
