package org.ggp.base.player.gamer.statemachine.mcts;

import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTree;
import org.ggp.base.player.gamer.statemachine.mcts.model.SearchTreeNode;
import org.ggp.base.player.gamer.statemachine.sample.SampleGamer;
import org.ggp.base.player.gamer.statemachine.sancho.ThreadControl;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MCTSGamer extends SampleGamer {

    private final long                            SAFETY_MARGIN = 2500;

    private ForwardDeadReckonPropnetStateMachine  underlyingStateMachine = null;
    private SearchTree                            tree = null;
    private                                       int turnCount = 0;

    @Override
    public StateMachine getInitialStateMachine() {
        underlyingStateMachine = new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS,
                getMetaGamingTimeout(),
                getRole(),
                mGameCharacteristics);

        System.gc();

        underlyingStateMachine.enableGreedyRollouts(false, true);
        tree = new SearchTree(underlyingStateMachine);
        turnCount = 1;

        return underlyingStateMachine;
    }

    @Override
    public Move stateMachineSelectMove(long xiTimeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {
        long finishBy = xiTimeout - SAFETY_MARGIN;
        int iterations = 0;

        System.out.println("Starting turn " + turnCount++);

        SearchTreeNode startRootNode = tree.findNode(getCurrentState());
        tree.cut(startRootNode);

        while(System.currentTimeMillis() < finishBy && !tree.isComplete())
        {
            iterations++;
            tree.grow();
        }

        Move bestMove = tree.getBestAction(getRole());
        System.out.println("Processed " + iterations + " iterations, and playing: " + bestMove);
        return bestMove;
    }
}
