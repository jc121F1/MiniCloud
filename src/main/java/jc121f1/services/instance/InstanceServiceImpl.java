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
import jc121f1.services.instance.compute.ComputeOutcomeException;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.InstanceHealthEvent;
import jc121f1.services.instance.exceptions.ConflictException;
import jc121f1.services.instance.exceptions.ResourceNotFoundException;
import jc121f1.services.instance.exceptions.ValidationException;
import jc121f1.services.instance.store.InstanceStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.Consumer;

@Slf4j
public class InstanceServiceImpl implements InstanceService, AutoCloseable {
    public static final Duration DEFAULT_STARTUP_DEADLINE = Duration.ofSeconds(120);
    private final Clock clock;
    private final Duration startupDeadline;

    private final ComputeBackend computeBackend;

    private final EventBus eventBus;

    private final InstanceStore instanceStore;
    private final AuthorizationService authorizationService;
    private final Consumer<InstanceHealthEvent> healthConsumer = this::handleHealthEvent;
    private boolean initialized;
    private boolean subscribed;
    private volatile boolean closed;
    private volatile long startupDeadlineNanos;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "computeBackend is an injected service dependency and is intentionally shared."
    )
    public InstanceServiceImpl(Clock clock, ComputeBackend computeBackend, EventBus eventBus,
                               InstanceStore instanceStore, AuthorizationService authorizationService) {
        this(clock, computeBackend, eventBus, instanceStore, authorizationService, DEFAULT_STARTUP_DEADLINE);
    }

    public InstanceServiceImpl(Clock clock, ComputeBackend computeBackend, EventBus eventBus,
                               InstanceStore instanceStore, AuthorizationService authorizationService,
                               Duration startupDeadline) {
        if (Objects.requireNonNull(startupDeadline, "startupDeadline").isZero()
                || startupDeadline.isNegative()) {
            throw new IllegalArgumentException("Startup deadline must be positive");
        }
        this.clock = clock;
        this.computeBackend = computeBackend;
        this.eventBus = eventBus;
        this.instanceStore = instanceStore;
        this.authorizationService = authorizationService;
        this.startupDeadline = startupDeadline;

    }

    /** Called by the runtime before opening the instance HTTP listener. */
    public synchronized void initialize() {
        if (closed) {
            throw new IllegalStateException("Instance service is closed");
        }
        if (initialized) {
            return;
        }
        try {
            subscribed = true;
            eventBus.subscribe(InstanceHealthEvent.class, healthConsumer);
            startupDeadlineNanos = System.nanoTime() + startupDeadline.toNanos();
            reconcileExistingInstances(startupDeadlineNanos)
                    .orTimeout(startupDeadline.toNanos(), TimeUnit.NANOSECONDS)
                    .join();
            initialized = true;
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw operationFailure(failure);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (subscribed) {
                eventBus.unsubscribe(InstanceHealthEvent.class, healthConsumer);
            }
        }
    }

    @Override
    public Instance get(AuthenticatedSession caller, GetInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        requireOpen();

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
        requireOpen();
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
        observeOperation(createdInstance, invokeBackend(() -> createInstance(createdInstance))
                .thenCompose(ignored -> computeBackend.start(createdInstance)), InstanceState.RUNNING);

        return returnedInstance;
    }

    @Override
    public List<Instance> list(AuthenticatedSession caller, ListInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
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
        requireOpen();
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
        if (!remove.state().isDeletable()) {
            throw new ConflictException("Instance {" + identifier + "} has an operation in progress. "
                    + "Current state is {" + remove.state() + "}");
        }
        Instance reserved = remove.state() == InstanceState.DELETING
                ? remove : setInstanceState(remove, InstanceState.DELETING);

        try {
            deleteReservedInstance(reserved).join();
        } catch (CompletionException error) {
            throw operationFailure(error);
        }


        return remove;
    }

    private CompletableFuture<Void> deleteReservedInstance(Instance instance) {
        // Keep DELETING after either failure: backend deletion may already have taken effect.
        return invokeBackend(() -> computeBackend.delete(instance))
                .thenCompose(ignored -> instanceStore.delete(instance));
    }

    @Override
    public Instance stop(AuthenticatedSession caller, StopInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
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
                    "Instance {" + identifier + "} is not in a stoppable state. " +
                            "Current state is {" + stop.state() + "}");
        }
        stopInstance(stop);

        return stop.copyOf();
    }

    @Override
    public Instance start(AuthenticatedSession caller, StartInstanceRequest request) {
        Objects.requireNonNull(caller, "caller");
        requireOpen();
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
        observeOperation(instance, invokeBackend(() -> computeBackend.start(instance)), InstanceState.RUNNING);
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
        observeOperation(instance, invokeBackend(() -> computeBackend.stop(instance)), InstanceState.STOPPED);
    }

    private CompletableFuture<Void> invokeBackend(Supplier<CompletableFuture<Void>> operation) {
        try {
            return operation.get();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Void> observeOperation(
            Instance instance, CompletableFuture<Void> operation, InstanceState success) {
        return observeOperation(instance, operation, success, false);
    }

    private CompletableFuture<Void> observeOperation(
            Instance instance, CompletableFuture<Void> operation, InstanceState success, boolean startupRecovery) {
        return operation.handle((ignored, error) -> {
            if (closed || (startupRecovery && System.nanoTime() - startupDeadlineNanos >= 0)) {
                // Shutdown or the startup deadline may end coordination while Docker work is still in flight.
                // Leave the reservation for startup reconciliation instead of guessing its outcome.
                return null;
            }
            if (error != null) {
                log.warn("Backend operation failed for instance {}", instance.id(), error);
            }
            try {
                InstanceState completionState = success;
                if (error != null) {
                    Throwable cause = error;
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof ComputeOutcomeException outcome) {
                        if (outcome.observedStatus() == null) {
                            return null;
                        }
                        completionState = switch (outcome.observedStatus()) {
                            case RUNNING -> InstanceState.RUNNING;
                            case STOPPED -> InstanceState.STOPPED;
                            case MISSING -> InstanceState.MISSING;
                        };
                    } else {
                        completionState = InstanceState.MISSING;
                    }
                }
                setInstanceState(instance, completionState);
            } catch (RuntimeException stateError) {
                // A stale completion must not overwrite a newer operation, even when its backend failed.
                log.warn("Unable to record operation completion for instance {}", instance.id(), stateError);
            }
            return null;
        });
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Instance service is closed");
        }
    }

    private RuntimeException operationFailure(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
    }

    private void handleHealthEvent(InstanceHealthEvent event) {
        if (closed) {
            return;
        }
        switch (event.action()) {
            case UNHEALTHY:
                instanceStore.get(event.instanceId())
                        .thenAccept(optional -> optional.ifPresent(instance -> {
                            if (!closed && instance.state() == InstanceState.RUNNING) {
                                setInstanceState(instance, InstanceState.MISSING);
                            }
                        })).exceptionally(error -> {
                            log.warn("Unable to apply health event for instance {}", event.instanceId(), error);
                            return null;
                        });
                break;
            case HEALTHY:
                instanceStore.get(event.instanceId())
                        .thenAccept(optional -> optional.ifPresent(instance -> {
                            if (!closed && instance.state() == InstanceState.MISSING) {
                                setInstanceState(instance, InstanceState.RUNNING);
                            }
                        })).exceptionally(error -> {
                            log.warn("Unable to apply health event for instance {}", event.instanceId(), error);
                            return null;
                        });
                break;
            default:
        }
    }

    private Instance replaceInstance(Instance previous, Instance newInstance) {
        if (previous == null || newInstance == null) {
            throw new IllegalArgumentException("Instances cannot be null");
        }

        try {
            return instanceStore.update(previous, newInstance).join();
        } catch (CompletionException error) {
            throw operationFailure(error);
        }
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

    private CompletableFuture<Void> reconcileExistingInstances(long deadlineNanos) {
        return instanceStore.list().thenCompose(instances -> {
            if (closed) {
                return CompletableFuture.failedFuture(new CancellationException("Instance service is closed"));
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                return CompletableFuture.failedFuture(new TimeoutException("Startup reconciliation deadline expired"));
            }
            if (instances.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            Map<String, ComputeStatus> statuses = computeBackend.describeStatuses(instances);
            CompletableFuture<Void> recovery = CompletableFuture.completedFuture(null);
            for (Instance instance : instances) {
                recovery = recovery.thenCompose(ignored -> {
                    if (closed) {
                        return CompletableFuture.failedFuture(new CancellationException("Instance service is closed"));
                    }
                    if (System.nanoTime() - deadlineNanos >= 0) {
                        return CompletableFuture.failedFuture(new TimeoutException("Startup reconciliation deadline expired"));
                    }
                    ComputeStatus status = statuses.getOrDefault(instance.id(), ComputeStatus.MISSING);
                    return reconcileInstance(instance, status);
                });
            }
            return recovery;
        });
    }

    private CompletableFuture<Void> reconcileInstance(Instance instance, ComputeStatus status) {
        return switch (instance.state()) {
            case RUNNING -> reconcileRunning(instance, status);
            case STOPPED -> reconcileStopped(instance, status);
            case STARTING -> reconcileStarting(instance, status);
            case STOPPING -> reconcileStopping(instance, status);
            case MISSING -> reconcileMissing(instance, status);
            case DELETING -> deleteReservedInstance(instance);
        };
    }

    private CompletableFuture<Void> reconcileRunning(Instance instance, ComputeStatus status) {
        if (status == ComputeStatus.RUNNING) {
            return CompletableFuture.completedFuture(null);
        }
        return instanceStore.update(instance, instance.toBuilder().state(InstanceState.STARTING).build())
                .thenCompose(reserved -> closed
                        ? CompletableFuture.completedFuture(null)
                        : reconcileStarting(reserved, status));
    }

    private CompletableFuture<Void> reconcileStopped(Instance instance, ComputeStatus status) {
        if (status == ComputeStatus.STOPPED) {
            return CompletableFuture.completedFuture(null);
        }
        return instanceStore.update(instance, instance.toBuilder().state(InstanceState.STOPPING).build())
                .thenCompose(reserved -> closed
                        ? CompletableFuture.completedFuture(null)
                        : reconcileStopping(reserved, status));
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

        return observeOperation(instance, invokeBackend(() -> reconcileToRunning(instance, status)),
                InstanceState.RUNNING, true);
    }

    private CompletableFuture<Void> reconcileStopping(
            Instance instance,
            ComputeStatus status) {
        if (status == ComputeStatus.MISSING) {
            return observeOperation(instance, CompletableFuture.completedFuture(null), InstanceState.MISSING, true);
        }
        return observeOperation(instance, invokeBackend(() -> reconcileToStopped(instance, status)),
                InstanceState.STOPPED, true);
    }

    private CompletableFuture<Void> reconcileMissing(Instance instance, ComputeStatus status) {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> reconcileToStopped(Instance instance, ComputeStatus status) {
        switch (status) {
            case RUNNING -> {
                return computeBackend.stop(instance);
            }
            default -> {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

}
