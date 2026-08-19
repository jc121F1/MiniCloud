package jc121f1.service.events;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.services.instance.events.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


@MiniCloudTest
class SimpleEventBusTest {

    private SimpleEventBus eventBus;

    // Direct execution keeps basic functional tests synchronous and predictable
    private final java.util.concurrent.Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        eventBus = new SimpleEventBus(directExecutor);
    }

    @Nested
    class Publishing_and_Routing {

        @Test
        void should_deliver_event_to_exact_type_subscriber() {
            List<String> receivedEvents = new ArrayList<>();
            eventBus.subscribe(String.class, receivedEvents::add);

            eventBus.publish("Hello, Nested Bus!");

            Assertions.assertEquals(1, receivedEvents.size());
            Assertions.assertEquals("Hello, Nested Bus!", receivedEvents.getFirst());
        }

        @Test
        void should_deliver_event_to_multiple_subscribers() {
            AtomicInteger counter1 = new AtomicInteger();
            AtomicInteger counter2 = new AtomicInteger();

            eventBus.subscribe(Integer.class, ignored -> counter1.incrementAndGet());
            eventBus.subscribe(Integer.class, ignored -> counter2.incrementAndGet());

            eventBus.publish(42);

            Assertions.assertEquals(1, counter1.get());
            Assertions.assertEquals(1, counter2.get());
        }

        @Test
        void should_ignore_subscribers_of_different_types() {
            List<String> stringEvents = new ArrayList<>();
            List<Integer> intEvents = new ArrayList<>();

            eventBus.subscribe(String.class, stringEvents::add);
            eventBus.subscribe(Integer.class, intEvents::add);

            eventBus.publish("Only Strings Here");

            Assertions.assertEquals(1, stringEvents.size());
            Assertions.assertTrue(intEvents.isEmpty(), "Integer subscriber should not receive String events");
        }

        @Test
        void should_silently_drop_events_with_no_subscribers() {
            Assertions.assertDoesNotThrow(() -> eventBus.publish("No one is listening"));
        }

        @Test
        void should_throw_null_pointer_exception_when_publishing_null() {
            // Because publish calls event.getClass()
            Assertions.assertThrows(NullPointerException.class, () -> eventBus.publish(null));
        }
    }

    @Nested
    class Unsubscribing {

        @Test
        void should_stop_delivering_events_after_unsubscribe() {
            AtomicInteger count = new AtomicInteger(0);
            Consumer<String> subscriber = event -> count.incrementAndGet();

            eventBus.subscribe(String.class, subscriber);
            eventBus.publish("First Event");

            eventBus.unsubscribe(String.class, subscriber);
            eventBus.publish("Second Event");

            Assertions.assertEquals(1, count.get(), "Subscriber should not receive events after unsubscribing");
        }

        @Test
        void should_safely_handle_unsubscribing_unknown_consumer() {
            Consumer<String> unknownSubscriber = event -> { };

            // Unsubscribing someone who never subscribed shouldn't throw exceptions
            Assertions.assertDoesNotThrow(() -> eventBus.unsubscribe(String.class, unknownSubscriber));
        }
    }

    @Nested
    class Concurrency {

        private ExecutorService realThreadPool;
        private SimpleEventBus asyncEventBus;

        @BeforeEach
        void setupAsync() {
            // Override the outer class setup with a real thread pool for concurrency tests
            realThreadPool = Executors.newFixedThreadPool(10);
            asyncEventBus = new SimpleEventBus(realThreadPool);
        }

        @AfterEach
        void tearDownAsync() {
            realThreadPool.shutdownNow();
        }

        @Test
        void should_handle_concurrent_modifications_and_publishes() throws InterruptedException {
            int count = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch publishLatch = new CountDownLatch(count);
            AtomicInteger totalEventsReceived = new AtomicInteger(0);

            // 1. Register all subscribers upfront
            for (int i = 0; i < count; i++) {
                asyncEventBus.subscribe(String.class, event -> totalEventsReceived.incrementAndGet());
            }

            // 2. Queue up the concurrent publishers
            for (int i = 0; i < count; i++) {
                realThreadPool.submit(() -> {
                    try {
                        startLatch.await(); // Wait for the green light
                        asyncEventBus.publish("Concurrent Event");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        publishLatch.countDown();
                    }
                });
            }

            // 3. Release all publishers simultaneously
            startLatch.countDown();

            // 4. Wait for all publish() methods to finish executing
            Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS), "Publishers did not complete in time");

            // 5. CRITICAL: Because publish() is asynchronous, we must wait for the executor
            // to finish draining the dispatch() tasks it just queued up.
            realThreadPool.shutdown();
            Assertions.assertTrue(realThreadPool.awaitTermination(5, TimeUnit.SECONDS), "Dispatching did not finish in time");

            // 100 publishers * 100 subscribers = exactly 10,000 deliveries
            Assertions.assertEquals(10000, totalEventsReceived.get());
        }
    }
}
