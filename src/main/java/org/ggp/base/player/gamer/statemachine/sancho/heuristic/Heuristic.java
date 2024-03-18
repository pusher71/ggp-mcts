package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * Interface for heuristics.
 *
 * <p>Each heuristic is used in 3 phases.
 *
 * <ol><li>Tuning
 *     <li>Validation and weighting
 *     <li>Move selection</ol>
 *
 * <p><b>Tuning</b>
 *
 * <p>During meta-gaming, Sancho initialises all heuristics and performs the tuning phase.  During this phase,
 * heuristics get the opportunity to collect data on a number of sample rollouts.  At the end of the phase, they may
 * set any internal tunable parameters.  There is no other output from this phase.
 *
 * <p><b>Validation and weighting</b>
 *
 * <p>During meta-gaming, Sancho attempts to determine whether each heuristic actually improves game-play and, if so,
 * the relative weight to apply to the heuristic.  The heuristic code should no take no special action during this phase
 * - it should simply act as in the move selection phase.
 *
 * <p>(Not yet implemented.)
 *
 * <p><b>Move selection</b>
 *
 * <p>During move selection, Sancho asks for the heuristic value of each node, on expansion.  Bearing in mind that
 * heuristics are intended to assist with move selection, Sancho uses the difference in heuristic value between the
 * current and new states.  This amplifies the effect of the heuristic (by removing any constant portion due to the
 * current state).
 *
 * <p>Heuristics should return an int[] for each queried state, where each value is in the range 0 - 100.  This should
 * be the best prediction of the final result of the game, according to the heuristic.
 *
 * <p>Heuristics should additionally return an indication of their confidence in the heuristic values.  This should be
 * in the range 0 - 10 where 0 indicates that the heuristic value is no better than a random number (and should
 * therefore be ignored) and 10 indicates the maximum certainty that this heuristic can ever have.  Heuristics with no
 * variable measure of their own confidence should always output 10.
 *
 * <p>Heuristics will not be asked for a value in a terminal state.
 */
public interface Heuristic
{
  /**
   * Class containing output information from heuristic evaluation
   * @author steve
   *
   */
  public class HeuristicInfo
  {
    /**
     * Whether this progression (from previous to current state as supplied to
     * the heuristic evaluation) should be regarded as a step in a heuristic sequence
     * if either the previous or next step in the election path is also so flagged.
     * Heuristic sequences will be treated as if they were single logical steps, with
     * the heuristic bias applied on exit from the sequence.  As such a heuristic should
     * avoid flagging all or most transitions in this manner.  It should also avoid
     * very strong heuristic values in conjunction with sequence flagging since the
     * update values propagated up through sequence exit points in th tree will be adjusted
     * towards 0 or 100 (depending on the heuristic weight being < or > 50) by an amount
     * dependent on that weight - a weight of 0 or 100 implies certainty and will force
     * all propagations to reflect that value, which will usually be incorrect when used
     * in this manner.
     * A heuristic need not flag anything as bing part of a sequence (in which case the heuristic
     * value will have a much more localized impact, and cause more/less initial exploration
     * as well as higher/lower initial estimates for a node's value.  Without sequence flagging
     * the effect of the heuristic on a node will decay as samples are added.
     */
    public boolean  treatAsSequenceStep;
    /**
     * Weight to apply the heuristic with
     */
    public double   heuristicWeight;
    /**
     * Role-indexed array of heuristic values.  These should reflect the degree to which
     * the current state is better (for each role) than the reference state (as presented to
     * the heuristic evaluator).  A value of 50 indicates equivalence, and the range is [0-100]
     */
    public double[] heuristicValue;

    /**
     * Constructor
     * @param numRoles
     */
    public HeuristicInfo(int numRoles)
    {
      heuristicValue = new double[numRoles];
    }
  }

  /**
   * Initialise the heuristic and prepare for tuning.
   *
   * @param stateMachine - the state machine representation of the game.
   * @param roleOrdering - the canonical role ordering.
   * @return true if at least one heuristic is potentially active
   */
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine stateMachine,
                                  RoleOrdering roleOrdering);

  /**
   * Indicates the start of a new sample game
   */
  public void tuningStartSampleGame();

  /**
   * Update tuning state as a result of a single step of a rollout (i.e. a single move in a game).
   *
   * @param state             - the new state.
   * @param choosingRoleIndex - the role to choose a move in this state.
   */
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState state, int choosingRoleIndex);

  /**
   * Update tuning state at the end of a single rollout (i.e. a sample match).
   *
   * @param state      - the terminal state.
   * @param roleScores - the goal values in the terminal state.
   */
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState state, int[] roleScores);

  /**
   * Complete the tuning phase, updating internal state as necessary.
   */
  public void tuningComplete();

  /**
   * Record the state of a turn.
   *
   * This method will always be called before {@link #getHeuristicValue} or {@link #getSampleWeight} and will be called
   * again at the start of each turn.
   *
   * @param xiState - the new state.
   * @param xiNode  - tree node representing the state.  !! ARR Hack, used by PieceHeuristic.
   */
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode);

  /**
   * Get the heuristic value for the specified state.
   *
   * @param xiState           - the state (never a terminal state).
   * @param xiPreviousState   - the previous state (can be null).
   * @param xiReferenceState  - state with which to compare to determine heuristic values
   */
  public void getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                  ForwardDeadReckonInternalMachineState xiPreviousState,
                                  ForwardDeadReckonInternalMachineState xiReferenceState,
                                  HeuristicInfo resultInfo);

  /**
   * @return whether the heuristic should be used.
   */
  public boolean isEnabled();

  /**
   * @return whether to apply using just initial value weights, as opposed to with
   * ongoing bias applied through update propagation
   */
  public boolean applyAsSimpleHeuristic();

  /**
   * @return an instance of the heuristic that can be used independently of existing instances on different game trees
   * (for the same game).
   *
   * An instance may return itself if it has no game-state-dependent persistent state.
   */
  public Heuristic createIndependentInstance();
}
