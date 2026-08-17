package jc121f1.services.instance.compute.docker;

import java.util.Arrays;
import java.util.Optional;

public enum EventAction {

    START("start"),
    DIE("die"),
    HEALTHY("health_status: healthy"),
    UNHEALTHY("health_status: unhealthy");

    private final String value;

    EventAction(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Optional<EventAction> fromValue(String value) {
        return Arrays.stream(values())
                .filter(action -> action.value.equals(value))
                .findFirst();
    }
}