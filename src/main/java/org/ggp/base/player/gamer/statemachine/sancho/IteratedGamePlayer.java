package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.StateMachineGamer;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class IteratedGamePlayer
{
  private static final Logger LOGGER = LogManager.getLogger();

  private ForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private StateMachineGamer gamer;
  private boolean isPseudoSimultaneousMove;
  private RoleOrdering roleOrdering;
  private Role ourRole;
  private double competitivenessBonus;
  private Random r = new Random();

  public IteratedGamePlayer(ForwardDeadReckonPropnetStateMachine stateMachine,
                            StateMachineGamer gamer,
                            boolean isPseudoSimultaneousMove,
                            RoleOrdering roleOrdering,
                            double competitivenessBonus)
  {
    underlyingStateMachine = stateMachine;
    this.gamer = gamer;
    this.isPseudoSimultaneousMove = isPseudoSimultaneousMove;
    this.roleOrdering  = roleOrdering;
    this.competitivenessBonus = competitivenessBonus;

    ourRole = roleOrdering.roleIndexToRole(0);
  }

  public Move selectMove(List<Move> moves, long timeout)
  {
    List<List<GdlTerm>> moveHistory = gamer.getMatch().getMoveHistory();
    List<Set<GdlSentence>> stateHistory = gamer.getMatch().getStateHistory();
    Move bestMove = null;
    Map<Move, Map<Move, Integer>> opponentMoveSelectionCounts = new HashMap<>();
    Move lastPlayedOpponentChoice = null;
    Move lastPlayedOurChoice = null;
    int lastPlayedOurChoiceSize = 0;

    if (moves.size() == 1)
    {
      return moves.get(0);
    }

    boolean responderInNonSimultaneousGame = (!isPseudoSimultaneousMove && moveHistory.size() % 2 == 1);

    for (int i = 0; i < moveHistory.size(); i++)
    {
      List<GdlTerm> moveTerms = moveHistory.get(i);
      MachineState state = new MachineState(stateHistory.get(i));
      List<Move> historicalJointMove = new ArrayList<>();
      for (GdlTerm sentence : moveTerms)
      {
        historicalJointMove.add(underlyingStateMachine.getMoveFromTerm(sentence));
      }

      //  Did our opponent have a choice?
      if (underlyingStateMachine.getLegalMoves(state, roleOrdering.roleIndexToRole(1)).size() > 1)
      {
        lastPlayedOpponentChoice = historicalJointMove.get(roleOrdering.roleIndexToRawRoleIndex(1));

        //  Was this following a previous move of ours?  If so bump the response counts
        //  for that move
        if (lastPlayedOurChoice != null)
        {
          Map<Move, Integer> moveWeights = opponentMoveSelectionCounts.get(lastPlayedOurChoice);
          if (moveWeights == null)
          {
            moveWeights = new HashMap<>();
            opponentMoveSelectionCounts.put(lastPlayedOurChoice, moveWeights);
          }

          Integer val = moveWeights.get(lastPlayedOpponentChoice);

          moveWeights.put(lastPlayedOpponentChoice,
                          (val == null ? lastPlayedOurChoiceSize : val + lastPlayedOurChoiceSize));
        }
      }

      //  Did we have a choice?
      if (underlyingStateMachine.getLegalMoves(state, roleOrdering.roleIndexToRole(0)).size() > 1)
      {
        lastPlayedOurChoice = historicalJointMove.get(roleOrdering.roleIndexToRawRoleIndex(0));
        lastPlayedOurChoiceSize = underlyingStateMachine.getLegalMoves(state, roleOrdering.roleIndexToRole(0)).size();

        if (lastPlayedOpponentChoice != null)
        {
          //  Bump for all moves we can play the count for the move the opponent played last
          for (Move legalMove : underlyingStateMachine.getLegalMoves(state, roleOrdering.roleIndexToRole(0)))
          {
            Map<Move, Integer> moveWeights = opponentMoveSelectionCounts.get(legalMove);
            if (moveWeights == null)
            {
              moveWeights = new HashMap<>();
              opponentMoveSelectionCounts.put(legalMove, moveWeights);
            }

            Integer val = moveWeights.get(lastPlayedOpponentChoice);

            moveWeights.put(lastPlayedOpponentChoice, (val == null ? 1 : val + 1));
          }
        }
      }
    }

    ForwardDeadReckonInternalMachineState currentState =
                                                    underlyingStateMachine.createInternalState(gamer.getCurrentState());

    int bestScore = -1;
    int opponentScoreAtBestScore = 0;
    for (Move move : moves)
    {
      ForwardDeadReckonInternalMachineState state = new ForwardDeadReckonInternalMachineState(currentState);
      ForwardDeadReckonInternalMachineState newState = new ForwardDeadReckonInternalMachineState(currentState);
      Map<Move, Integer> moveWeights = opponentMoveSelectionCounts.get(move);

      LOGGER.info("Considering move: " + move);
      if (responderInNonSimultaneousGame)
      {
        LOGGER.info("We are responder so assuming opponent continues to play " + lastPlayedOpponentChoice);
      }
      else if (moveWeights != null)
      {
        LOGGER.info("Response weights: " + moveWeights.values());
      }

      while (!underlyingStateMachine.isTerminal(state))
      {
        ForwardDeadReckonLegalMoveInfo[] jointMove = new ForwardDeadReckonLegalMoveInfo[2];

        for (int roleIndex = 0; roleIndex < underlyingStateMachine.getRoles().size(); roleIndex++)
        {
          Role role = roleOrdering.roleIndexToRole(roleIndex);
          Collection<ForwardDeadReckonLegalMoveInfo> roleMoves = underlyingStateMachine.getLegalMoves(state, role);

          if (roleMoves.size() == 1)
          {
            jointMove[roleIndex] = roleMoves.iterator().next();
          }
          else
          {
            Map<Move,ForwardDeadReckonLegalMoveInfo> legalMoveMap = new HashMap<>();

            for(ForwardDeadReckonLegalMoveInfo moveInfo : roleMoves)
            {
              legalMoveMap.put(moveInfo.mMove, moveInfo);
            }

            if (role.equals(ourRole))
            {
              if (!legalMoveMap.containsKey(move))
              {
                LOGGER.warn("Unexpectedly cannot play intended move in iterated game!");
              }
              jointMove[roleIndex] = legalMoveMap.get(move);
            }
            else if (responderInNonSimultaneousGame)
            {
              jointMove[roleIndex] = legalMoveMap.get(lastPlayedOpponentChoice);
            }
            else
            {
              //  Do we have opponent response stats for this move?
              if (moveWeights == null)
              {
                //  Assume flat distribution
                moveWeights = new HashMap<>();

                for (Move m : legalMoveMap.keySet())
                {
                  moveWeights.put(m, 1);
                }
              }

              int total = 0;
              for (Integer weight : moveWeights.values())
              {
                total += weight;
              }

              int rand = r.nextInt(total);
              for (Move m : legalMoveMap.keySet())
              {
                Integer weight = moveWeights.get(m);
                if (weight != null)
                {
                  rand -= weight;
                }

                if (rand < 0)
                {
                  jointMove[roleIndex] = legalMoveMap.get(m);
                  break;
                }
              }
            }
          }
        }

        underlyingStateMachine.getNextState(state, null, jointMove, newState);

        state.copy(newState);
      }

      int score = underlyingStateMachine.getGoal(ourRole);
      int opponentScore = underlyingStateMachine.getGoal(roleOrdering.roleIndexToRole(1));

      if (score >= opponentScore)
      {
        score += competitivenessBonus;
        if (score > opponentScore)
        {
          score += competitivenessBonus;
        }
      }
      if (score > bestScore ||
          (score == bestScore && opponentScoreAtBestScore > opponentScore))
      {
        bestScore = score;
        opponentScoreAtBestScore = opponentScore;
        bestMove = move;
      }
    }

    StatsLogUtils.Series.SCORE.logDataPoint(Math.max(0, bestScore));
    return bestMove;
  }
}
