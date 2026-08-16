package jc121f1.services.instance;

import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.CreateInstanceRequest;
import jc121f1.model.instance.api.DeleteInstanceRequest;
import jc121f1.model.instance.api.GetInstanceRequest;
import jc121f1.model.instance.api.ListInstanceRequest;
import jc121f1.model.instance.api.StartInstanceRequest;
import jc121f1.model.instance.api.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;

import javax.inject.Inject;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InstanceServiceImpl implements InstanceService {
    private final Map<String, Instance> instancesById = new HashMap<>();
    private final Map<String, String> idsByName = new HashMap<>();
    private final Clock clock;

    @Inject
    public InstanceServiceImpl(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Instance get(GetInstanceRequest request) {
        Instance instance;
        String identifier;

        if (request.getInstanceId() == null && request.getName() == null) {
            throw new IllegalArgumentException(
                    "GetInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.getInstanceId() != null) {
            identifier = request.getInstanceId();
        } else {
            identifier = request.getName();
        }

        instance = getByIdOrName(identifier);

        return instance;
    }

    @Override
    public synchronized Instance create(CreateInstanceRequest request) {
        String instanceId;
        Instance createdInstance;

        if (idsByName.containsKey(request.getName())) {
            throw new IllegalArgumentException("An instance with name '" + request.getName() + "' already exists");
        }
        instanceId = "i-" + UUID.randomUUID();
        createdInstance = Instance.builder()
                .cpu(request.getCpu())
                .name(request.getName())
                .memory(request.getMemory())
                .id(instanceId)
                .state(InstanceState.STARTING)
                .createdAt(clock.instant())
                .build();

        instancesById.put(instanceId, createdInstance);
        idsByName.put(request.getName(), instanceId);

        return createdInstance;
    }

    @Override
    public synchronized List<Instance> list(ListInstanceRequest request) {
        return list();
    }

    @Override
    public synchronized List<Instance> list() {
        return List.copyOf(instancesById.values());
    }

    @Override
    public synchronized Instance delete(DeleteInstanceRequest request) {
        Instance remove;
        String identifier;

        if (request.getInstanceId() == null && request.getName() == null) {
            throw new IllegalArgumentException(
                    "DeleteInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.getInstanceId() != null) {
            identifier = request.getInstanceId();
        } else {
            identifier = request.getName();
        }
        remove = getByIdOrName(identifier);

        instancesById.remove(remove.getId());
        idsByName.remove(remove.getName());

        return remove;
    }

    @Override
    public synchronized Instance stop(StopInstanceRequest request) {
        Instance stop;
        String identifier;

        if (request.getInstanceId() == null && request.getName() == null) {
            throw new IllegalArgumentException(
                    "StopInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.getInstanceId() != null) {
            identifier = request.getInstanceId();
        } else {
            identifier = request.getName();
        }
        stop = getByIdOrName(identifier);

        if (stop.getState().isStoppable()) {
            stop.setState(InstanceState.STOPPING);
        } else {
            throw new IllegalArgumentException(
                    "Instance {" + identifier + "} is not in a startable state. " +
                            "Current state is {" + stop.getState() + "}");
        }

        return stop;
    }

    @Override
    public synchronized Instance start(StartInstanceRequest request) {
        Instance start;
        String identifier;

        if (request.getInstanceId() == null && request.getName() == null) {
            throw new IllegalArgumentException(
                    "StopInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.getInstanceId() != null) {
            identifier = request.getInstanceId();
        } else {
            identifier = request.getName();
        }
        start = getByIdOrName(identifier);

        if (start.getState().isStartable()) {
            start.setState(InstanceState.STARTING);
        } else {
            throw new IllegalArgumentException(
                    "Instance {" + identifier + "} is not in a startable state. " +
                            "Current state is {" + start.getState() + "}");
        }

        return start;
    }

    private Instance getByIdOrName(String identifier) {
        String id = idsByName.getOrDefault(identifier, identifier);

        return Optional.ofNullable(instancesById.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Resource not found"));
    }
}
