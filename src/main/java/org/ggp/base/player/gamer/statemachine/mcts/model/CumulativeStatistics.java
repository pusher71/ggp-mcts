package org.ggp.base.player.gamer.statemachine.mcts.model;

public class CumulativeStatistics {

    private RoleActionStatistics roleActionStatistics;
    private int numVisits;

    public CumulativeStatistics() {
        roleActionStatistics = new RoleActionStatistics();
        numVisits = 0;
    }

    public RoleActionStatistics getRoleActionStatistics() {
        return roleActionStatistics;
    }

    public int getNumVisits() {
        return numVisits;
    }

    public void incNumVisits() {
        numVisits++;
    }
}
