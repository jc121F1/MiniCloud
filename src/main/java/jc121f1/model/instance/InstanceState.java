package jc121f1.model.instance;

import java.util.List;

public enum InstanceState {
    STARTING,
    STOPPED,
    STOPPING,
    RUNNING,
    MISSING;

    private static final List<InstanceState> STARTABLE_STATES = List.of(STOPPED);
    private static final List<InstanceState> STOPPABLE_STATES = List.of(RUNNING);
    private static final List<InstanceState> TERMINAL_STATES = List.of(STOPPING, STOPPED, MISSING);
    public boolean isStartable() {
        return STARTABLE_STATES.contains(this);
    }

    public boolean isStoppable() {
        return STOPPABLE_STATES.contains(this);
    }

    public boolean isTransitioning() {
        return STARTABLE_STATES.contains(this) || STOPPABLE_STATES.contains(this);
    }

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }
}
