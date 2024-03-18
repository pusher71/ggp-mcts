package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * Specialised game player for games which can be represented by a simple payoff matrix.
 *
 * Currently limited to 2-player single-move games.  Iterated games are allowed, provided that each iteration is a
 * single-move and the number of iterations is fixed.  Iterated Stag Hunt is an example of a supported game.  It has
 * 20 iterations of a payoff matrix that looks as follows.
 *
 * 1\2  | Stag | Hare |
 * -----+------+------+
 * Stag | 5,5  | 0,2  |
 * Hare | 2,0  | 2,2  |
 *
 */
public class PayoffMatrixGamePlayer
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * The number of turns in the game.  (This player only handles fixed-length games.)
   */
  private int mNumTurns;

  /**
   * The roles in the game.
   */
  private List<Role> mRoles;

  /**
   * The legals for each role.  (This player only handles games where the legals don't change between turns.)
   */
  private List<Move>[] mLegalsForRole;

  /**
   * The payoff matrix for this game, indexed by (player 0 move index, player 1 move index, player index for score).
   *
   * For example, the value in mPayoff[A][B][0] is the score for player 0 when player 0 plays A and player 1 plays B.
   */
  private int[][][] mPayoff;

  /**
   * Create a payoff matrix game player for the game represented by the specified propnet.
   *
   * @param xiStateMachine - a state machine for simulating the game.
   * @param xiTimeout      - the time at which analysis must stop.
   *
   * @throws UnsupportedGameException if the game isn't a simple payoff matrix game or couldn't be determined to be so
   *                                  by the specified time.
   */
  public PayoffMatrixGamePlayer(ForwardDeadReckonPropnetStateMachine xiStateMachine, long xiTimeout)
    throws UnsupportedGameException
  {
    analyseGame(xiStateMachine, xiTimeout);
  }

  /**
   * Analyse a game (defined by a state machine) to see whether it is a payoff-matrix game.
   *
   * @param xiStateMachine - the state machine.
   * @param xiTimeout      - the time at which analysis must stop.
   *
   * @throws UnsupportedGameException if analysis fails to demonstrate that this is a payoff-matrix game.
   */
  @SuppressWarnings("unchecked")
  private void analyseGame(ForwardDeadReckonPropnetStateMachine xiStateMachine, long xiTimeout)
    throws UnsupportedGameException
  {
    // If the game isn't a 2-player game, reject it out of hand.  (No reason why this restriction can't be relaxed in
    // future if required.)
    mRoles = xiStateMachine.getFullPropNet().getRoles();
    if (mRoles.size() != 2)
    {
      throw new UnsupportedGameException("Not a 2-player game");
    }

    // Build the set of components that are reachable from the input (DOES) propositions.
    Set<PolymorphicComponent> lReachable = new HashSet<>();
    for (PolymorphicProposition lInputProp : xiStateMachine.getFullPropNet().getInputPropositions().values())
    {
      buildReachablesFrom(lInputProp, lReachable, xiTimeout);
    }

    // If the terminal proposition is part of the context, that means nothing any player can do will affect the length
    // of the game - i.e. it is a provably fixed length game.  For now, we only support games that are provably fixed-
    // length.
    if (lReachable.contains(xiStateMachine.getFullPropNet().getTerminalProposition()))
    {
      throw new UnsupportedGameException("Can't prove that the game is fixed length");
    }
    LOGGER.debug("Provably fixed length game");

    // Check whether all the LEGALs are in the context.  If any aren't, we don't support this game with this player (and
    // don't ever intend to).
    for (PolymorphicProposition lLegals[] : xiStateMachine.getFullPropNet().getLegalPropositions().values())
    {
      for (PolymorphicProposition lLegal : lLegals)
      {
        if (lReachable.contains(lLegal))
        {
          throw new UnsupportedGameException("At least one LEGAL is affected by player actions");
        }
      }
    }
    LOGGER.debug("All LEGALs are part of the context");

    // Since we now know that the game is fixed length and that nothing that any player can do can affect the legal
    // moves, perform a single random playout and see what those moves are.
    //
    // If any set of moves differs from the moves available in the initial state, this isn't a single-move iterated
    // game and we don't support it with this player.
    MachineState lState = xiStateMachine.getInitialState();
    mLegalsForRole = new List[mRoles.size()];
    for (int lii = 0; lii < mRoles.size(); lii++)
    {
      mLegalsForRole[lii] = xiStateMachine.getLegalMoves(lState, mRoles.get(lii));
    }

    mNumTurns = 0;
    while (!xiStateMachine.isTerminal(lState))
    {
      mNumTurns++;

      // Check that the LEGALs are the same.
      for (int lii = 0; lii < mRoles.size(); lii++)
      {
        List<Move> lLegals = xiStateMachine.getLegalMoves(lState, mRoles.get(lii));
        if (!lLegals.equals(mLegalsForRole[lii]))
        {
          throw new UnsupportedGameException("LEGALs not constant for role " + lii);
        }
      }

      // Get a next state (any one will do).
      try
      {
        lState = xiStateMachine.getRandomNextState(lState);
      }
      catch (MoveDefinitionException | TransitionDefinitionException lEx)
      {
        LOGGER.error("Simulation failed", lEx);
        throw new UnsupportedGameException("Simulation error: " + lEx);
      }
    }

    // Now we know that the game is in a suitable form.  (Actually, it still might not be.  We could have a fixed-length
    // game with the same set of legal moves in each state, but the payoffs differ between turns.  However, I don't
    // think there are any of those coded up yet.)
    //
    // Determine the payoffs.
    buildPayoffMatrix(xiStateMachine, xiTimeout);

    // Find any pure-strategy Nash Equilibria.
    findPureStrategyNE();
  }

  /**
   * Recursively compute the list of components that are reachable from (i.e. downstream of) the specified component.
   *
   * @param xiComponent - the component to start at.
   * @param xbReachable - the components already reached and the set into which further components are to be added.
   * @param xiTimeout   - the time at which searching must stop.
   *
   * @throws UnsupportedGameException if the timer expires during the search.
   */
  private void buildReachablesFrom(PolymorphicComponent xiComponent,
                                   Set<PolymorphicComponent> xbReachable,
                                   long xiTimeout) throws UnsupportedGameException
  {
    // If this component is already marked as reachable then stop searching down this branch (because we've already
    // been down it).
    if (xbReachable.contains(xiComponent))
    {
      return;
    }

    // Mark this component as reachable.
    xbReachable.add(xiComponent);

    // Abort if we're out of time.
    timeCheck(xiTimeout);

    // Recursively find all components reachable from this one.
    for (PolymorphicComponent lOutput : xiComponent.getOutputs())
    {
      buildReachablesFrom(lOutput, xbReachable, xiTimeout);
    }
  }

  /**
   * Build a payoff matrix for the specified game.  The game must already have passed the basic sanity tests before this
   * routine is called.
   *
   * @param xiStateMachine - the state machine defining the game.
   * @param xiTimeout      - the time at which analysis must stop.
   *
   * @throws UnsupportedGameException in the unlikely event that the game isn't actually a payoff matrix game after all
   *                                  (or we run out of time building the matrix).
   */
  private void buildPayoffMatrix(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                 long xiTimeout)
    throws UnsupportedGameException
  {
    // Create the matrix.
    mPayoff = new int[mLegalsForRole[0].size()][mLegalsForRole[1].size()][2];

    MachineState lInitialState = xiStateMachine.getInitialState();

    // Build the payoff matrix.  We could do it analytically, but simpler for now to play the same moves every turn and
    // then get the average score.  (Repeat over all joint moves.)
    try
    {
      // Iterate over all joint moves.
      for (List<Move> lJointMove : xiStateMachine.getLegalJointMoves(lInitialState))
      {
        timeCheck(xiTimeout);

        int lP0MoveIndex = mLegalsForRole[0].indexOf(lJointMove.get(0));
        int lP1MoveIndex = mLegalsForRole[1].indexOf(lJointMove.get(1));

        // Rollout a game where the chosen set of joint moves are played at every turn.
        MachineState lState;
        for (lState = xiStateMachine.getInitialState();
            !xiStateMachine.isTerminal(lState);
            lState = xiStateMachine.getNextState(lState, lJointMove))
        { /* Do nothing */ }

        // Get the scores in the terminal state.
        List<Integer> lScores = xiStateMachine.getGoals(lState);
        for (int lii = 0; lii < lScores.size(); lii++)
        {
          // Get the total score and compute the average.  If it isn't a whole number, then the payoffs can't be
          // consistent over the lifetime of the game and this player can't be used.  (This only works if all the
          // players started with a score of 0, but all games do at the moment.)
          int lTotalScore = lScores.get(lii);
          if (lTotalScore % mNumTurns != 0)
          {
            throw new UnsupportedGameException("Payoffs vary over the game");
          }
          lScores.set(lii, lTotalScore / mNumTurns);
        }

        LOGGER.info("Payoffs when playing " + lJointMove + " are " + lScores);
        mPayoff[lP0MoveIndex][lP1MoveIndex][0] = lScores.get(0);
        mPayoff[lP0MoveIndex][lP1MoveIndex][1] = lScores.get(1);

      }
    }
    catch (MoveDefinitionException | GoalDefinitionException lEx)
    {
      LOGGER.error("Simulation failed", lEx);
      throw new UnsupportedGameException("Simulation error: " + lEx);
    }
  }

  /**
   * Find the pure strategy Nash Equilibria (if any) of the payoff matrix for this game.
   */
  private void findPureStrategyNE()
  {
    // Find any pure-strategy Nash Equilibria in the naive way.  Simply consider each cell and then look to see if any
    // player can do better by deviating.
    int lNumNEs = 0;
    for (int lP0MoveIndex = 0; lP0MoveIndex < mLegalsForRole[0].size(); lP0MoveIndex++)
    {
      for (int lP1MoveIndex = 0; lP1MoveIndex < mLegalsForRole[1].size(); lP1MoveIndex++)
      {
        // Assume this is an NE until we discover otherwise.
        boolean lNE = true;

        // See if P0 can do better by deviating.
        for (int lP0TrialIndex = 0; lP0TrialIndex < mLegalsForRole[0].size(); lP0TrialIndex++)
        {
          if (mPayoff[lP0TrialIndex][lP1MoveIndex][0] > mPayoff[lP0MoveIndex][lP1MoveIndex][0])
          {
            lNE = false;
            break;
          }
        }

        // See if P1 can do better by deviating.
        for (int lP1TrialIndex = 0; lP1TrialIndex < mLegalsForRole[0].size(); lP1TrialIndex++)
        {
          if (mPayoff[lP0MoveIndex][lP1TrialIndex][1] > mPayoff[lP0MoveIndex][lP1MoveIndex][1])
          {
            lNE = false;
            break;
          }
        }

        if (lNE)
        {
          LOGGER.info("[" + mLegalsForRole[0].get(lP0MoveIndex) + ", " + mLegalsForRole[1].get(lP1MoveIndex) + "] " +
                      "is an NE");
          lNumNEs++;
        }
      }
    }

    LOGGER.info("Found " + lNumNEs + " pure strategy Nash equilibria");
  }

  private static void timeCheck(long xiTimeout) throws UnsupportedGameException
  {
    // Abort if we're out of time.
    if (System.currentTimeMillis() > xiTimeout)
    {
      throw new UnsupportedGameException("Ran out of time during analysis");
    }
  }

  /**
   * Exception thrown when the game can't be proved to be supported by the PayoffMatrixGamePlayer.
   */
  public static class UnsupportedGameException extends Exception
  {
    private static final long serialVersionUID = 1L;

    /**
     * @param xiDetail - exception detail.
     */
    public UnsupportedGameException(String xiDetail)
    {
      super(xiDetail);
      LOGGER.info("Not suitable for PayoffMatrixGamePlayer: " + xiDetail);
    }
  }
}
