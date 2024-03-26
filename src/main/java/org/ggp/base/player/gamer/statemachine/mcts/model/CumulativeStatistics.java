package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Role;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CumulativeStatistics {

    private Map<Role, RoleActionStatistics> roleActionStatisticsMap;
    private int numVisits;

    public CumulativeStatistics(List<Role> roles) {
        roleActionStatisticsMap = new HashMap<>();
        for (Role role : roles) {
            roleActionStatisticsMap.put(role, new RoleActionStatistics());
        }
        numVisits = 0;
    }

    public RoleActionStatistics getRoleActionStatistics(Role role) {
        return roleActionStatisticsMap.get(role);
    }

    public int getNumVisits() {
        return numVisits;
    }

    public void incNumVisits() {
        numVisits++;
    }
}
