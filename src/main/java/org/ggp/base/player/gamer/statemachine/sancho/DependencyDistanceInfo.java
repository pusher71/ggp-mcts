package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 *  Class holding calculated dependency distance information for a game
 */
public class DependencyDistanceInfo
{
  /**
   * Distances (in turns) at which a pair of moves can both influence a common base prop
   * This matrix is symmetric
   */
  public int[][] moveCoInfluenceDistances;

  /**
   * Distance (in turns) at which the first move can enable (or disable) the legality
   * of the second move
   * Note - strictly through the action of moves of the same role.  This is a different
   * distance (at lest as far) as we would get if we allowed coupling through opposite
   * role moves, but for the purposes we put it we want the same-role-only variant
   * This matrix is not necessarily symmetric
   */
  public int[][] moveEnablingDistances;
}
