package jc121f1.services.instance.events;

import java.util.function.Consumer;

public interface EventBus {

    <T> void subscribe(Class<T> eventType, Consumer<T> consumer);

    <T> void unsubscribe(Class<T> eventType, Consumer<T> consumer);

    void publish(Object event);
}
