package jc121f1.model.instance;

import java.util.List;

public enum InstanceState {
    STARTING,
    STOPPED,
    STOPPING,
    RUNNING,
    DELETING,
    MISSING;

    private static final List<InstanceState> STARTABLE_STATES = List.of(STOPPED);
    private static final List<InstanceState> STOPPABLE_STATES = List.of(RUNNING);
    private static final List<InstanceState> TERMINAL_STATES = List.of(STOPPING, STOPPED, DELETING, MISSING);
    public boolean isStartable() {
        return STARTABLE_STATES.contains(this);
    }

    public boolean isStoppable() {
        return STOPPABLE_STATES.contains(this);
    }

    public boolean isTransitioning() {
        return this == STARTING || this == STOPPING || this == DELETING;
    }

    public boolean isDeletable() {
        return this == STOPPED || this == RUNNING || this == MISSING || this == DELETING;
    }

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }
}
