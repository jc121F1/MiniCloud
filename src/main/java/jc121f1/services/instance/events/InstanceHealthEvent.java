package jc121f1.services.instance.events;

import jc121f1.services.instance.compute.docker.EventAction;

public record InstanceHealthEvent(String instanceId, EventAction action) {
}
