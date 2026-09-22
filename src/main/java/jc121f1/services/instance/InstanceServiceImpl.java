package jc121f1.services.instance;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.instance.authorization.InstanceAction;
import jc121f1.services.instance.authorization.InstanceResourceType;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.api.request.ListInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.InstanceHealthEvent;
import jc121f1.services.instance.exceptions.ConflictException;
import jc121f1.services.instance.exceptions.ResourceNotFoundException;
import jc121f1.services.instance.exceptions.ValidationException;
import jc121f1.services.instance.store.InstanceStore;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class InstanceServiceImpl implements InstanceService {
    private final Clock clock;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "computeBackend is an injected service dependency and is intentionally shared."
    )
    private final ComputeBackend computeBackend;

    private final EventBus eventBus;

    private final InstanceStore instanceStore;
    private final AuthorizationService authorizationService;

    @Inject
    public InstanceServiceImpl(Clock clock, ComputeBackend computeBackend, EventBus eventBus,
                               InstanceStore instanceStore, AuthorizationService authorizationService) {
        this.clock = clock;
        this.computeBackend = computeBackend;
        this.eventBus = eventBus;
        this.instanceStore = instanceStore;
        this.authorizationService = authorizationService;

        this.registerHealthEvents();
        reconcileExistingInstances().exceptionally(error -> {
            log.warn("Unable to reconcile existing instances during startup", error);
            return null;
        });
    }

    private void registerHealthEvents() {
        eventBus.subscribe(InstanceHealthEvent.class, this::handleHealthEvent);
    }

    @Override
    public Instance get(AuthenticatedSession caller, GetInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");

        if (!request.hasIdentifier()) {
            throw new ValidationException(
                    "GetInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.hasInstanceId()) {
            Instance instance = instanceStore.get(request.instanceId()).join()
                    .orElseThrow(() -> new ResourceNotFoundException("Instance not found " + request.instanceId()));
            authorize(caller, InstanceAction.DESCRIBE, instance);
            return instance;
        } else {
            Instance instance = instanceStore.getByName(request.name()).join()
                    .orElseThrow(() -> new ResourceNotFoundException("Instance not found " + request.name()));
            authorize(caller, InstanceAction.DESCRIBE, instance);
            return instance;
        }
    }

    private Instance get(String identifier) {
        Optional<Instance> instance = instanceStore.get(identifier).join();

        return instance.orElseGet(() -> instanceStore.getByName(identifier)
                .join()
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Instance not found: " + identifier
                        )
                ));

    }

    @Override
    public Instance create(AuthenticatedSession caller, CreateInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        authorizationService.authorize(caller, InstanceAction.CREATE, accountResource(caller.accountId()));
        String instanceId;
        Instance createdInstance;

        instanceId = "i-" + UUID.randomUUID();
        createdInstance = Instance.builder()
                .cpu(request.cpu())
                .name(request.name())
                .memory(request.memory())
                .id(instanceId)
                .accountId(caller.accountId())
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
    public List<Instance> list(AuthenticatedSession caller, ListInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        authorizationService.authorize(caller, InstanceAction.LIST, accountResource(caller.accountId()));
        return instanceStore.list().join().stream()
                .filter(instance -> caller.accountId().equals(instance.accountId()))
                .filter(instance -> authorizationService.evaluate(caller, InstanceAction.DESCRIBE,
                        instanceResource(instance)).outcome() == AuthorizationDecision.Outcome.ALLOW)
                .toList();
    }

    private List<Instance> list() {
        return List.copyOf(instanceStore.list().join());
    }

    @Override
    public Instance delete(AuthenticatedSession caller, DeleteInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        Instance remove;
        String identifier;

        if (request.instanceId() == null && request.name() == null) {
            throw new ValidationException(
                    "DeleteInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.instanceId() != null) {
            identifier = request.instanceId();
        } else {
            identifier = request.name();
        }
        remove = get(identifier);
        authorize(caller, InstanceAction.DELETE, remove);

        computeBackend.delete(remove)
                .thenCompose(_ -> instanceStore.delete(remove));


        return remove;
    }

    @Override
    public Instance stop(AuthenticatedSession caller, StopInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        Instance stop;
        String identifier;

        if (request.instanceId() == null && request.name() == null) {
            throw new ValidationException(
                    "StopInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.instanceId() != null) {
            identifier = request.instanceId();
        } else {
            identifier = request.name();
        }
        stop = get(identifier);
        authorize(caller, InstanceAction.STOP, stop);

        if (stop.state().isStoppable()) {
            stop = setInstanceState(stop, InstanceState.STOPPING);
        } else {
            throw new ConflictException(
                    "Instance {" + identifier + "} is not in a startable state. " +
                            "Current state is {" + stop.state() + "}");
        }
        stopInstance(stop);

        return stop.copyOf();
    }

    @Override
    public Instance start(AuthenticatedSession caller, StartInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        Instance start;
        String identifier;

        if (request.instanceId() == null && request.name() == null) {
            throw new ValidationException(
                    "StopInstanceRequest must contain one of [\"name\" or \"instanceId\"]");
        } else if (request.instanceId() != null) {
            identifier = request.instanceId();
        } else {
            identifier = request.name();
        }
        start = get(identifier);
        authorize(caller, InstanceAction.START, start);
        if (start.state().isStartable()) {
            start = setInstanceState(start, InstanceState.STARTING);
        } else {
            throw new ConflictException(
                    "Instance {" + identifier + "} is not in a startable state. " +
                            "Current state is {" + start.state() + "}");
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

    private void authorize(AuthenticatedSession caller, InstanceAction action, Instance instance) {
        authorizationService.authorize(caller, action, instanceResource(instance));
    }

    private ResourceReference instanceResource(Instance instance) {
        return ResourceReference.of(InstanceAction.DESCRIBE.service(), instance.accountId(),
                InstanceResourceType.INSTANCE, instance.id());
    }

    private ResourceReference accountResource(String accountId) {
        return ResourceReference.of(InstanceAction.CREATE.service(), accountId,
                InstanceResourceType.ACCOUNT, accountId);
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
        switch (event.action()) {
            case UNHEALTHY:
                instanceStore.get(event.instanceId())
                        .thenAccept(optional -> optional.ifPresent(instance -> {
                            if (!instance.state().isTerminal()) {
                                setInstanceState(instance, InstanceState.MISSING);
                            }
                        }));
                break;
            case HEALTHY:
                instanceStore.get(event.instanceId())
                        .thenAccept(optional -> optional.ifPresent(instance -> {
                            if (instance.state() == InstanceState.MISSING) {
                                setInstanceState(instance, InstanceState.RUNNING);
                            }
                        }));
                break;
            default:
        }
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

    private CompletableFuture<Void> reconcileExistingInstances() {
        return instanceStore.list().thenCompose(instances -> {
            if (instances.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            Map<String, ComputeStatus> statuses = computeBackend.describeStatuses(instances);
            return CompletableFuture.allOf(
                                instances.stream()
                                        .map(instance -> {
                                            ComputeStatus status =
                                                    statuses.getOrDefault(
                                                            instance.id(),
                                                            ComputeStatus.MISSING);

                                            return switch (instance.state()) {
                                                case RUNNING ->
                                                        reconcileRunning(instance, status);
                                                case STOPPED ->
                                                        reconcileStopped(instance, status);
                                                case STARTING ->
                                                        reconcileStarting(instance, status);
                                                case STOPPING ->
                                                        reconcileStopping(instance, status);
                                                case MISSING ->
                                                        reconcileMissing(instance, status);
                                            };
                                        })
                                        .toArray(CompletableFuture[]::new)
                        );
        });
    }

    private CompletableFuture<Void> reconcileRunning(Instance instance, ComputeStatus status) {
        return reconcileToRunning(instance, status)
                .exceptionally(error -> markInstanceMissing(instance, error));
    }

    private CompletableFuture<Void> reconcileStopped(Instance instance, ComputeStatus status) {
        return reconcileToStopped(instance, status)
                .exceptionally(error -> markInstanceMissing(instance, error));
    }

    private CompletableFuture<Void> reconcileToRunning(Instance instance, ComputeStatus status) {
        switch (status) {
            case MISSING -> {
                return createInstance(instance)
                        .thenCompose(ignored -> computeBackend.start(instance));
            }
            case STOPPED -> {
                return computeBackend.start(instance);
            }
            default -> {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private CompletableFuture<Void> reconcileStarting(
            Instance instance,
            ComputeStatus status) {

        return reconcileToRunning(instance, status)
                .thenRun(() -> setInstanceState(instance, InstanceState.RUNNING))
                .exceptionally(error -> markInstanceMissing(instance, error));
    }

    private CompletableFuture<Void> reconcileStopping(
            Instance instance,
            ComputeStatus status) {

        return reconcileToStopped(instance, status)
                .thenRun(() -> setInstanceState(instance, InstanceState.STOPPED))
                .exceptionally(error -> markInstanceMissing(instance, error));
    }

    private CompletableFuture<Void> reconcileMissing(Instance instance, ComputeStatus status) {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> reconcileToStopped(Instance instance, ComputeStatus status) {
        switch (status) {
            case MISSING -> {
                return createInstance(instance);
            }
            case RUNNING -> {
                return computeBackend.stop(instance);
            }
            default -> {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private Void markInstanceMissing(Instance instance, Throwable error) {
        log.warn("Failed to reconcile instance {}", instance.id(), error);
        setInstanceState(instance, InstanceState.MISSING);
        return null;
    }
}
