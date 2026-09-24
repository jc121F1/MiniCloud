package jc121f1.service.instance;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.ResourceReference;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.api.request.ListInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.authorization.InstanceAction;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.store.InstanceStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@MiniCloudTest
class InstanceAuthorizationTest {
    private static final AuthenticatedSession CALLER =
            new AuthenticatedSession("a-1", "u-1", Session.SubjectType.USER);
    private static final Instance OWNED = Instance.builder().id("i-1").name("one")
            .accountId("a-1").state(InstanceState.RUNNING).build();
    private static final Instance OTHER = Instance.builder().id("i-2").name("other")
            .accountId("a-2").state(InstanceState.RUNNING).build();
    private static final Instance DENIED = Instance.builder().id("i-3").name("denied")
            .accountId("a-1").state(InstanceState.RUNNING).build();

    @Mock private ComputeBackend backend;
    @Mock private EventBus events;
    @Mock private InstanceStore store;
    @Mock private AuthorizationService authorization;
    private InstanceServiceImpl service;

    @BeforeEach
    void setup() {
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of()));
        service = new InstanceServiceImpl(Clock.systemUTC(), backend, events, store, authorization);
        Mockito.clearInvocations(store, backend, authorization);
    }

    @Test
    void createUsesCallerAccountAndChecksBeforeStorageOrCompute() {
        deny(InstanceAction.CREATE);

        Assertions.assertThatThrownBy(() -> service.create(CALLER, createRequest()))
                .isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verify(authorization).authorize(CALLER, InstanceAction.CREATE, accountResource());
        Mockito.verifyNoInteractions(backend);
        Mockito.verifyNoInteractions(store);

        Mockito.reset(authorization);
        Mockito.when(store.create(Mockito.any())).thenAnswer(call ->
                CompletableFuture.completedFuture(call.getArgument(0)));
        Mockito.when(store.update(Mockito.any(), Mockito.any())).thenAnswer(call ->
                CompletableFuture.completedFuture(call.getArgument(1)));
        Mockito.when(backend.create(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(backend.start(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Instance created = service.create(CALLER, createRequest());

        Assertions.assertThat(created.accountId()).isEqualTo(CALLER.accountId());
        Mockito.verify(store).create(Mockito.argThat(instance -> CALLER.accountId().equals(instance.accountId())));
    }

    @Test
    void concreteOperationsUsePersistedOwnerAndDenyBeforeSideEffects() {
        Mockito.when(store.get("i-2")).thenReturn(CompletableFuture.completedFuture(Optional.of(OTHER)));
        for (InstanceAction action : List.of(InstanceAction.DESCRIBE, InstanceAction.START,
                InstanceAction.STOP, InstanceAction.DELETE)) {
            deny(action);
            Assertions.assertThatThrownBy(() -> call(action))
                    .isInstanceOf(AuthorizationDeniedException.class);
            Mockito.verify(authorization).authorize(CALLER, action, instanceResource(OTHER));
            Mockito.reset(authorization);
        }
        Mockito.verify(store, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verify(store, Mockito.never()).delete(Mockito.any());
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void allowedConcreteOperationsUseTheirOwnActions() {
        Instance stopped = OWNED.toBuilder().state(InstanceState.STOPPED).build();
        Mockito.when(store.get("i-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(OWNED)));
        Mockito.when(store.update(Mockito.any(), Mockito.any())).thenAnswer(call ->
                CompletableFuture.completedFuture(call.getArgument(1)));
        Mockito.when(backend.stop(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(backend.delete(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(store.delete(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));

        service.get(CALLER, GetInstanceRequest.builder().instanceId("i-1").build());
        service.stop(CALLER, StopInstanceRequest.builder().instanceId("i-1").build());
        service.delete(CALLER, DeleteInstanceRequest.builder().instanceId("i-1").build());
        Mockito.verify(authorization).authorize(CALLER, InstanceAction.DESCRIBE, instanceResource(OWNED));
        Mockito.verify(authorization).authorize(CALLER, InstanceAction.STOP, instanceResource(OWNED));
        Mockito.verify(authorization).authorize(CALLER, InstanceAction.DELETE, instanceResource(OWNED));

        Mockito.when(store.get("i-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(stopped)));
        Mockito.when(backend.start(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        service.start(CALLER, StartInstanceRequest.builder().instanceId("i-1").build());
        Mockito.verify(authorization).authorize(CALLER, InstanceAction.START, instanceResource(stopped));
    }

    @Test
    void listRequiresAccountGrantAndDescribeGrantForEachReturnedInstance() {
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(OWNED, DENIED, OTHER)));
        deny(InstanceAction.LIST);
        Assertions.assertThatThrownBy(() -> service.list(CALLER, new ListInstanceRequest()))
                .isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verify(store, Mockito.never()).list();

        Mockito.reset(authorization);
        Mockito.when(authorization.evaluate(CALLER, InstanceAction.DESCRIBE, instanceResource(OWNED)))
                .thenReturn(new AuthorizationDecision(AuthorizationDecision.Outcome.ALLOW,
                        AuthorizationDecision.Reason.POLICY_ALLOW, List.of()));
        Mockito.when(authorization.evaluate(CALLER, InstanceAction.DESCRIBE, instanceResource(DENIED)))
                .thenReturn(new AuthorizationDecision(AuthorizationDecision.Outcome.DENY,
                        AuthorizationDecision.Reason.EXPLICIT_DENY, List.of()));
        Assertions.assertThat(service.list(CALLER, new ListInstanceRequest())).containsExactly(OWNED);
        Mockito.verify(authorization).authorize(CALLER, InstanceAction.LIST, accountResource());
        Mockito.verify(authorization, Mockito.never()).evaluate(CALLER, InstanceAction.DESCRIBE,
                instanceResource(OTHER));
    }

    @Test
    void authorizationStorageFailureIsNotConvertedToDenial() {
        Mockito.when(store.get("i-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(OWNED)));
        Mockito.doThrow(new AuthorizationStoreException("db failed", null))
                .when(authorization).authorize(CALLER, InstanceAction.DESCRIBE, instanceResource(OWNED));

        Assertions.assertThatThrownBy(() -> service.get(CALLER,
                GetInstanceRequest.builder().instanceId("i-1").build()))
                .isInstanceOf(AuthorizationStoreException.class);
    }

    @Test
    void listFailsIfDescribeEvaluationCannotReadPolicies() {
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(OWNED)));
        Mockito.when(authorization.evaluate(CALLER, InstanceAction.DESCRIBE, instanceResource(OWNED)))
                .thenThrow(new AuthorizationStoreException("db failed", null));

        Assertions.assertThatThrownBy(() -> service.list(CALLER, new ListInstanceRequest()))
                .isInstanceOf(AuthorizationStoreException.class);
    }

    private void call(InstanceAction action) {
        switch (action) {
            case DESCRIBE -> service.get(CALLER, GetInstanceRequest.builder().instanceId("i-2").build());
            case START -> service.start(CALLER, StartInstanceRequest.builder().instanceId("i-2").build());
            case STOP -> service.stop(CALLER, StopInstanceRequest.builder().instanceId("i-2").build());
            case DELETE -> service.delete(CALLER, DeleteInstanceRequest.builder().instanceId("i-2").build());
            default -> throw new IllegalArgumentException("Unsupported action");
        }
    }

    private void deny(InstanceAction action) {
        Mockito.doThrow(new AuthorizationDeniedException())
                .when(authorization).authorize(CALLER, action,
                        action == InstanceAction.CREATE || action == InstanceAction.LIST
                                ? accountResource() : instanceResource(OTHER));
    }

    private CreateInstanceRequest createRequest() {
        return CreateInstanceRequest.builder().name("new").cpu(1).memory(1).build();
    }

    private ResourceReference accountResource() {
        return new ResourceReference("instance", "a-1", "account", "a-1");
    }

    private ResourceReference instanceResource(Instance instance) {
        return new ResourceReference("instance", instance.accountId(), "instance", instance.id());
    }
}
