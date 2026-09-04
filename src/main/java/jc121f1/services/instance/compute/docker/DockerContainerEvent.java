package jc121f1.services.instance.compute.docker;

public record DockerContainerEvent(
        String containerId,
        EventAction action
) {
}
