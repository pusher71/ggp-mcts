package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 * Buffer structure to hold basic info about a state
 */
class StateInfo
{
  /**
   * Is this state terminal
   */
  public boolean isTerminal;
  /**
   * If it is terminal, with what scores
   */
  public final double[] terminalScore;

  /**
   * Construct a new state info buffer
   * @param numRoles
   */
  StateInfo(int numRoles)
  {
    terminalScore = new double[numRoles];
  }
}