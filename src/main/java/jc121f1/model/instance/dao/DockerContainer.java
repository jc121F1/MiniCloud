package jc121f1.model.instance.dao;

import jc121f1.model.instance.ComputeStatus;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class DockerContainer {
    private final String id;
    private final String name;
    private final String instanceId;
    private final ComputeStatus status;
}
