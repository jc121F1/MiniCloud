package jc121f1.model.instance;

import com.github.dockerjava.api.model.Container;

public enum ComputeStatus {
    RUNNING,
    STOPPED,
    MISSING;

    public static ComputeStatus fromDockerContainer(Container container) {
        return "running".equalsIgnoreCase(container.getState())
                ? ComputeStatus.RUNNING
                : ComputeStatus.STOPPED;
    }
}
