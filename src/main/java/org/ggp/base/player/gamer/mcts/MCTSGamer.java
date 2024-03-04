package org.ggp.base.player.gamer.mcts;

import org.ggp.base.player.gamer.Gamer;
import org.ggp.base.player.gamer.exception.*;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.gdl.grammar.GdlTerm;

public class MCTSGamer extends Gamer {
    @Override
    public void metaGame(long timeout) throws MetaGamingException {

    }

    @Override
    public GdlTerm selectMove(long timeout) throws MoveSelectionException {
        return null;
    }

    @Override
    public void stop() throws StoppingException {

    }

    @Override
    public void abort() throws AbortingException {

    }

    @Override
    public void preview(Game g, long timeout) throws GamePreviewException {

    }

    @Override
    public String getName() {
        return null;
    }
}
