package org.ggp.base.player.gamer.statemachine.mcts.model.strategy;

public class PoolOfStrategies {

    private SelectionStrategy selectionStrategy;
    private SelectionStrategyForMatch selectionStrategyForMatch;
    private ExpansionStrategy expansionStrategy;
    private PlayoutStrategy playoutStrategy;
    private CuttingStrategy cuttingStrategy;

    public PoolOfStrategies() {
        selectionStrategy = new SelectionStrategy();
        selectionStrategyForMatch = new SelectionStrategyForMatch();
        expansionStrategy = new ExpansionStrategy();
        playoutStrategy = new PlayoutStrategy();
        cuttingStrategy = new CuttingStrategy();
    }

    public SelectionStrategy getSelectionStrategy() {
        return selectionStrategy;
    }

    public SelectionStrategyForMatch getSelectionStrategyForMatch() {
        return selectionStrategyForMatch;
    }

    public ExpansionStrategy getExpansionStrategy() {
        return expansionStrategy;
    }

    public PlayoutStrategy getPlayoutStrategy() {
        return playoutStrategy;
    }

    public CuttingStrategy getCuttingStrategy() {
        return cuttingStrategy;
    }
}
