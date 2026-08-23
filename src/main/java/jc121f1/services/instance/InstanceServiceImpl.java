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
import jc121f1.services.instance.store.InstanceStore;

import javax.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class InstanceServiceImpl implements InstanceService {
    private final Clock clock;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "computeBackend is an injected service dependency and is intentionally shared."
    )
    private final ComputeBackend computeBackend;

    private final EventBus eventBus;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "instanceStore is an injected service dependency and is intentionally shared."
    )
    private final InstanceStore instanceStore;

    @Inject
    public InstanceServiceImpl(Clock clock, ComputeBackend computeBackend, EventBus eventBus,
                               InstanceStore instanceStore) {
        this.clock = clock;
        this.computeBackend = computeBackend;
        this.eventBus = eventBus;
        this.instanceStore = instanceStore;

        this.registerHealthEvents();
    }

    private void registerHealthEvents() {
        eventBus.subscribe(InstanceHealthEvent.class, this::handleHealthEvent);
    }

    @Override
    public Instance get(GetInstanceRequest request) {

        if (!request.hasIdentifier()) {
            throw new IllegalArgumentException(
                    "GetInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.hasInstanceId()) {
            return instanceStore.get(request.getInstanceId()).join()
                    .orElseThrow(() -> new IllegalStateException("Instance not found " + request.getInstanceId()));
        } else {
            return instanceStore.getByName(request.getName()).join()
                    .orElseThrow(() -> new IllegalStateException("Instance not found " + request.getName()));
        }
    }

    private Instance get(String identifier) {
        Optional<Instance> instance = instanceStore.get(identifier).join();

        return instance.orElseGet(() -> instanceStore.getByName(identifier)
                .join()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Instance not found: " + identifier
                        )
                ));

    }

    @Override
    public Instance create(CreateInstanceRequest request) {
        String instanceId;
        Instance createdInstance;

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

        instanceStore.create(createdInstance).join();
        createInstance(createdInstance)
                .thenRun(() -> startInstance(createdInstance))
                .exceptionally(error -> {
                    setInstanceState(createdInstance, InstanceState.MISSING);
                    return null;
                });

        return returnedInstance;
    }

    @Override
    public List<Instance> list(ListInstanceRequest request) {
        return instanceStore.list().join();
    }

    private List<Instance> list() {
        return List.copyOf(instanceStore.list().join());
    }

    @Override
    public Instance delete(DeleteInstanceRequest request) {
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
        remove = get(identifier);

        computeBackend.delete(remove)
                .thenCompose(_ -> instanceStore.delete(remove));


        return remove;
    }

    @Override
    public Instance stop(StopInstanceRequest request) {
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
        stop = get(identifier);

        if (stop.getState().isStoppable()) {
            stop = setInstanceState(stop, InstanceState.STOPPING);
        } else {
            throw new IllegalArgumentException(
                    "Instance {" + identifier + "} is not in a startable state. " +
                            "Current state is {" + stop.getState() + "}");
        }
        stopInstance(stop);

        return stop.copyOf();
    }

    @Override
    public Instance start(StartInstanceRequest request) {
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
        start = get(identifier);
        if (start.getState().isStartable()) {
            start = setInstanceState(start, InstanceState.STARTING);
        } else {
            throw new IllegalArgumentException(
                    "Instance {" + identifier + "} is not in a startable state. " +
                            "Current state is {" + start.getState() + "}");
        }
        startInstance(start);
        return start.copyOf();
    }

    private void startInstance(Instance instance) {
        computeBackend.start(instance)
                .thenRun(() ->
                        setInstanceState(instance, InstanceState.RUNNING))
                .exceptionally(error -> {
                    setInstanceState(instance, InstanceState.MISSING);
                    return null;
                });
    }

    private CompletableFuture<Void> createInstance(Instance instance) {
        return computeBackend.create(instance);
    }

    private void stopInstance(Instance instance) {
        computeBackend.stop(instance)
                .thenRun(() ->
                        setInstanceState(instance, InstanceState.STOPPED))
                .exceptionally(error -> {
                    setInstanceState(instance, InstanceState.MISSING);
                    return null;
                });
    }

    private void handleHealthEvent(InstanceHealthEvent event) {
        if (event.action() != EventAction.UNHEALTHY) {
            return;
        }

        instanceStore.get(event.instanceId())
                .thenAccept(optional -> optional.ifPresent(instance -> {
                    if (!instance.getState().isTerminal()) {
                        setInstanceState(instance, InstanceState.MISSING);
                    }
                }));
    }

    private Instance replaceInstance(Instance previous, Instance newInstance) {
        if (previous == null || newInstance == null) {
            throw new IllegalArgumentException("Instances cannot be null");
        }

        return instanceStore.update(previous, newInstance).join();
    }

    private Instance setInstanceState(
            Instance instance,
            InstanceState state
    ) {
        return replaceInstance(instance,
                instance.toBuilder()
                        .state(state)
                        .build()
        );
    }
}
