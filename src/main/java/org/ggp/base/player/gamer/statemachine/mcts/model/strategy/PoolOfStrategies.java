package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

public class PoolOfStrategies {

    private SelectionStrategy selectionStrategy;
    private SelectionStrategyForMatch selectionStrategyForMatch;
    private ExpansionStrategy expansionStrategy;
    private PlayoutStrategy playoutStrategy;

    private CuttingStrategy cuttingStrategy;
}
