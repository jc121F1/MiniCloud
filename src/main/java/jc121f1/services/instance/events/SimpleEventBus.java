package jc121f1.services.instance.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class SimpleEventBus implements EventBus {

    private final Executor executor;
    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    public SimpleEventBus(Executor executor) {
        this.executor = executor;
    }

    @Override
    public <T> void subscribe(Class<T> eventType, Consumer<T> consumer) {
        subscribers
                .computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>())
                .add(consumer);
    }

    @Override
    public <T> void unsubscribe(Class<T> eventType, Consumer<T> consumer) {
        List<Consumer<?>> consumers = subscribers.get(eventType);
        if (consumers != null) {
            consumers.remove(consumer);
        }
    }

    @Override
    public void publish(Object event) {
        Class<?> eventClass = event.getClass();

        // Exact match dispatch
        List<Consumer<?>> consumers = subscribers.get(eventClass);
        if (consumers != null) {
            for (Consumer<?> consumer : consumers) {
                executor.execute(() -> dispatch(consumer, event));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(Consumer<?> consumer, Object event) {
        ((Consumer<Object>) consumer).accept(event);
    }
}