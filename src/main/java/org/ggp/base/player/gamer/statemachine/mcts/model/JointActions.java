package org.ggp.base.player.gamer.statemachine.mcts.model;

import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

import java.util.*;

public class JointActions {

    private transient List<Move> actionsList;
    private Map<Role, Move> actionsMap;

    public JointActions() {
        actionsMap = new HashMap<>();
    }

    public JointActions(List<Move> actionsList, List<Role> roles) {
        this.actionsList = actionsList;
        actionsMap = new HashMap<>();
        for (int i = 0; i < actionsList.size(); i++) {
            Role role = roles.get(i);
            Move action = actionsList.get(i);
            actionsMap.put(role, action);
        }
    }

    public List<Move> toList() {
        return actionsList;
    }

    public Move getActionByRole(Role role) {
        return actionsMap.get(role);
    }

    public void addAction(Role role, Move action) {
        actionsMap.put(role, action);
    }
}
