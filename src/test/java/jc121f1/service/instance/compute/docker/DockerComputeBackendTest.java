package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.HostConfig;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.events.EventBus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@MiniCloudTest
public class DockerComputeBackendTest {

    private static final String INSTANCE_ID = "i-123";
    private static final String CONTAINER_ID = "container-123";
    private static final int CPU = 2;
    private static final int MEMORY = 1024;

    @Mock
    private DockerClient dockerClient;

    @Mock
    private DockerEventListener eventListener;

    @Mock
    private Instance instance;

    @Mock
    private CreateContainerCmd createContainerCmd;

    @Mock
    private CreateContainerResponse createContainerResponse;

    @Mock
    private StartContainerCmd startContainerCmd;

    @Mock
    private StopContainerCmd stopContainerCmd;

    @Mock
    private RemoveContainerCmd removeContainerCmd;

    @Mock
    private EventBus eventBus;

    private Executor executor;

    private DockerComputeBackend computeBackend;

    @BeforeEach
    void setup() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        computeBackend = new DockerComputeBackend(
                dockerClient,
                eventListener,
                eventBus,
                executor
        );

        Mockito.when(instance.getId()).thenReturn(INSTANCE_ID);
        Mockito.lenient().when(instance.getCpu()).thenReturn(CPU);
        Mockito.lenient().when(instance.memoryInBytes()).thenReturn((long) MEMORY);
    }

    @Nested
    class When_creating_an_instance {

        @BeforeEach
        void setup() {
            Mockito.when(dockerClient.createContainerCmd("jc121f1/alpine"))
                    .thenReturn(createContainerCmd);

            Mockito.when(createContainerCmd.withHostConfig(Mockito.any(HostConfig.class)))
                    .thenReturn(createContainerCmd);

            Mockito.when(createContainerCmd.withName("MiniCloud-" + INSTANCE_ID))
                    .thenReturn(createContainerCmd);

            Mockito.when(createContainerCmd.exec())
                    .thenReturn(createContainerResponse);

            Mockito.when(createContainerResponse.getId())
                    .thenReturn(CONTAINER_ID);
        }

        @Test
        void It_should_create_a_container() {
            computeBackend.create(instance).join();

            Mockito.verify(dockerClient)
                    .createContainerCmd("jc121f1/alpine");

            Mockito.verify(createContainerCmd).exec();
        }

        @Test
        void It_should_create_the_container_with_the_instance_name() {
            computeBackend.create(instance).join();

            Mockito.verify(createContainerCmd)
                    .withName("MiniCloud-" + INSTANCE_ID);
        }

        @Test
        void It_should_complete_successfully() {
            Assertions.assertThat(computeBackend.create(instance).join()).isNull();
        }
    }

    @Nested
    class When_starting_an_instance {

        private CompletableFuture<Event> eventFuture;

        @BeforeEach
        void setup() {
            createContainer();

            eventFuture = CompletableFuture.completedFuture(
                    Mockito.mock(Event.class)
            );

            Mockito.when(eventListener.waitFor(
                    CONTAINER_ID,
                    EventAction.START
            )).thenReturn(eventFuture);

            Mockito.when(dockerClient.startContainerCmd(CONTAINER_ID))
                    .thenReturn(startContainerCmd);
        }

        @Test
        void It_should_wait_for_a_start_event() {
            computeBackend.start(instance).join();

            Mockito.verify(eventListener)
                    .waitFor(CONTAINER_ID, EventAction.START);
        }

        @Test
        void It_should_start_the_container() {
            computeBackend.start(instance).join();

            Mockito.verify(dockerClient)
                    .startContainerCmd(CONTAINER_ID);

            Mockito.verify(startContainerCmd).exec();
        }

        @Test
        void It_should_complete_when_the_start_event_is_received() {
            Assertions.assertThat(computeBackend.start(instance).join())
                    .isNull();
        }

        @Test
        void It_should_propagate_a_start_failure() {
            RuntimeException exception =
                    new RuntimeException("start failed");

            CompletableFuture<Event> failedFuture =
                    CompletableFuture.failedFuture(exception);

            Mockito.when(eventListener.waitFor(
                    CONTAINER_ID,
                    EventAction.START
            )).thenReturn(failedFuture);

            Assertions.assertThatThrownBy(() -> computeBackend.start(instance).join())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    class When_stopping_an_instance {

        private CompletableFuture<Event> eventFuture;

        @BeforeEach
        void setup() {
            createContainer();

            eventFuture = CompletableFuture.completedFuture(
                    Mockito.mock(Event.class)
            );

            Mockito.when(eventListener.waitFor(
                    CONTAINER_ID,
                    EventAction.DIE
            )).thenReturn(eventFuture);

            Mockito.when(dockerClient.stopContainerCmd(CONTAINER_ID))
                    .thenReturn(stopContainerCmd);
        }

        @Test
        void It_should_wait_for_a_die_event() {
            computeBackend.stop(instance).join();

            Mockito.verify(eventListener)
                    .waitFor(CONTAINER_ID, EventAction.DIE);
        }

        @Test
        void It_should_stop_the_container() {
            computeBackend.stop(instance).join();

            Mockito.verify(dockerClient)
                    .stopContainerCmd(CONTAINER_ID);

            Mockito.verify(stopContainerCmd).exec();
        }

        @Test
        void It_should_complete_when_the_die_event_is_received() {
            Assertions.assertThat(computeBackend.stop(instance).join())
                    .isNull();
        }

        @Test
        void It_should_propagate_a_stop_failure() {
            RuntimeException exception =
                    new RuntimeException("stop failed");

            CompletableFuture<Event> failedFuture =
                    CompletableFuture.failedFuture(exception);

            Mockito.when(eventListener.waitFor(
                    CONTAINER_ID,
                    EventAction.DIE
            )).thenReturn(failedFuture);

            Assertions.assertThatThrownBy(() -> computeBackend.stop(instance).join());
        }
    }

    @Nested
    class When_deleting_an_instance {

        @BeforeEach
        void setup() {
            createContainer();

            Mockito.when(dockerClient.removeContainerCmd(CONTAINER_ID))
                    .thenReturn(removeContainerCmd);

            Mockito.when(removeContainerCmd.withForce(true))
                    .thenReturn(removeContainerCmd);
        }

        @Test
        void It_should_remove_the_container() {
            computeBackend.delete(instance).join();

            Mockito.verify(dockerClient)
                    .removeContainerCmd(CONTAINER_ID);

            Mockito.verify(removeContainerCmd).exec();
        }

        @Test
        void It_should_complete_successfully() {
            Assertions.assertThat(computeBackend.delete(instance).join())
                    .isNull();
        }

        @Test
        void It_should_no_longer_have_a_container_for_the_instance() {
            computeBackend.delete(instance).join();

            Assertions.assertThatThrownBy(
                    () -> computeBackend.start(instance).join()
            ).hasMessageContaining("No Docker container exists");
        }
    }

    @Nested
    class When_an_instance_has_no_container {

        @Test
        void Start_should_throw() {
            Assertions.assertThatThrownBy(
                    () -> computeBackend.start(instance).join()
            ).hasMessageContaining("No Docker container exists");
        }

        @Test
        void Stop_should_throw() {
            Assertions.assertThatThrownBy(
                    () -> computeBackend.stop(instance).join()
            ).hasMessageContaining("No Docker container exists");
        }

        @Test
        void Delete_should_throw() {
            Assertions.assertThatThrownBy(
                    () -> computeBackend.delete(instance).join()
            ).hasMessageContaining("No Docker container exists");
        }
    }

    @Nested
    class When_closing {

        @BeforeEach
        void setup() {
            createContainer();

            Mockito.when(dockerClient.stopContainerCmd(CONTAINER_ID))
                    .thenReturn(stopContainerCmd);
        }

        @Test
        void It_should_close_the_event_listener() throws Exception {
            computeBackend.close();

            Mockito.verify(eventListener).close();
        }

        @Test
        void It_should_stop_the_container() throws Exception {
            computeBackend.close();

            Mockito.verify(dockerClient)
                    .stopContainerCmd(CONTAINER_ID);

            Mockito.verify(stopContainerCmd).exec();
        }

        @Test
        void It_should_close_the_docker_client() throws Exception {
            computeBackend.close();

            Mockito.verify(dockerClient).close();
        }
    }

    private void createContainer() {
        Mockito.when(dockerClient.createContainerCmd("jc121f1/alpine"))
                .thenReturn(createContainerCmd);

        Mockito.when(createContainerCmd.withHostConfig(Mockito.any(HostConfig.class)))
                .thenReturn(createContainerCmd);

        Mockito.when(createContainerCmd.withName("MiniCloud-" + INSTANCE_ID))
                .thenReturn(createContainerCmd);

        Mockito.when(createContainerCmd.exec())
                .thenReturn(createContainerResponse);

        Mockito.when(createContainerResponse.getId())
                .thenReturn(CONTAINER_ID);

        computeBackend.create(instance).join();
    }
}