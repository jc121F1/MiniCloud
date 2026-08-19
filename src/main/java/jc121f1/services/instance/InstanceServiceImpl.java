package jc121f1.services.instance;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.api.request.ListInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.compute.docker.EventAction;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.InstanceHealthEvent;

import javax.inject.Inject;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class InstanceServiceImpl implements InstanceService {
    private final Map<String, Instance> instancesById = new HashMap<>();
    private final Map<String, String> idsByName = new HashMap<>();

    private final Clock clock;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "computeBackend is an injected service dependency and is intentionally shared."
    )
    private final ComputeBackend computeBackend;

    private final EventBus eventBus;

    @Inject
    public InstanceServiceImpl(Clock clock, ComputeBackend computeBackend, EventBus eventBus) {
        this.clock = clock;
        this.computeBackend = computeBackend;
        this.eventBus = eventBus;

        this.registerHealthEvents();
    }

    private void registerHealthEvents() {
        eventBus.subscribe(InstanceHealthEvent.class, this::handleHealthEvent);
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

        Instance returnedInstance = createdInstance.toBuilder().build();

        instancesById.put(instanceId, createdInstance);
        idsByName.put(request.getName(), instanceId);

        createInstance(createdInstance)
                .thenRun(() -> startInstance(createdInstance));

        return returnedInstance;
    }

    @Override
    public synchronized List<Instance> list(ListInstanceRequest request) {
        return list();
    }

    private synchronized List<Instance> list() {
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

        computeBackend.delete(remove);
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
        Instance copy = stop.toBuilder().build();
        stopInstance(stop);

        return copy;
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
        Instance copy = start.toBuilder().build();
        startInstance(start);
        return copy;
    }

    private Instance getByIdOrName(String identifier) {
        String id = idsByName.getOrDefault(identifier, identifier);

        return Optional.ofNullable(instancesById.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Resource not found"));
    }

    private void startInstance(Instance instance) {
        computeBackend.start(instance)
                .thenRun(() ->
                        instance.setState(InstanceState.RUNNING))
                .exceptionally(error -> {
                    instance.setState(InstanceState.MISSING);
                    return null;
                });
    }

    private CompletableFuture<Void> createInstance(Instance instance) {
        return computeBackend.create(instance);
    }

    private void stopInstance(Instance instance) {
        computeBackend.stop(instance)
                .thenRun(() ->
                        instance.setState(InstanceState.STOPPED))
                .exceptionally(error -> {
                    instance.setState(InstanceState.MISSING);
                    return null;
                });
    }

    private synchronized void handleHealthEvent(InstanceHealthEvent event) {
        Optional<Instance> optionalInstance = Optional.ofNullable(instancesById.get(event.instanceId()));
        if (Objects.requireNonNull(event.action()) == EventAction.UNHEALTHY) {
            optionalInstance.ifPresent(instance -> {
                if (instance.getState().isTerminal()) {
                    instancesById.put(instance.getId(), instance.toBuilder().state(InstanceState.MISSING).build());
                }
            });
        }
    }
}
