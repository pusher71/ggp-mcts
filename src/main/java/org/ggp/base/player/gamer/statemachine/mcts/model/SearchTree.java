package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.player.gamer.statemachine.mcts.model.strategy.PoolOfStrategies;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class SearchTree {

    private StateMachine gameModel;
    private SearchTreeNode root;
    private PoolOfStrategies strategies;

    public SearchTree(StateMachine gameModel) {
        this.gameModel = gameModel;
        MachineState rootState = gameModel.getInitialState();
        root = new SearchTreeNode(this, gameModel.getRoles().get(0), rootState, null);
        strategies = new PoolOfStrategies();
    }

    public SearchTreeNode findNode(MachineState state) {
        return root.findNode(state);
    }

    public void cut(SearchTreeNode startRootNode) {
        getStrategies().getCuttingStrategy().execute(this, startRootNode);
    }

    public void grow() throws MoveDefinitionException {

        // Получить выборку узлов для выбора того, который будет раскрыт
        List<SearchTreeNode> list = new ArrayList<>();
        getStrategies().getSelectionStrategy().execute(root, list);

        // Выбрать случайный узел из полученной выборки
        SearchTreeNode selectedNode = list.get(ThreadLocalRandom.current().nextInt(list.size()));

        // Раскрыть и выбрать случайное созданное дитё
        selectedNode = getStrategies().getExpansionStrategy().execute(selectedNode);

        // Отыграть с него
        Map<Role, Double> playoutScore = getStrategies().getPlayoutStrategy().execute(selectedNode);

        // Распространить полученные очки
        getStrategies().getSelectionStrategy().backPropagation(selectedNode, playoutScore);
    }

    public Move getBestAction(Role choosingRole) {
        return root.getBestAction(choosingRole);
    }

    public boolean isComplete() {
        return root.isComplete();
    }

    public StateMachine getGameModel() {
        return gameModel;
    }

    public void setRoot(SearchTreeNode newRoot) {
        root = newRoot;
    }

    public PoolOfStrategies getStrategies() {
        return strategies;
    }
}
