package jc121f1.services.instance;

import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.CreateInstanceRequest;
import jc121f1.model.instance.api.GetInstanceRequest;
import jc121f1.model.instance.api.ListInstanceRequest;
import jc121f1.model.instance.dao.Instance;

import javax.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class InstanceServiceImpl implements InstanceService {
    private final Map<String, Instance> instances = new ConcurrentHashMap<>();

    private final Clock clock;

    @Inject
    public InstanceServiceImpl(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instance get(GetInstanceRequest request) {
        return null;
    }

    @Override
    public Instance create(CreateInstanceRequest request) {
        if (instances.values().stream().anyMatch(instance -> instance.getName().equals(request.getName()))) {
            throw new IllegalArgumentException("An instance with name '" + request.getName() + "' already exists");
        }

        String instanceId = "i-" + UUID.randomUUID();

        Instance createdInstance = Instance.builder()
                .cpu(request.getCpu())
                .name(request.getName())
                .memory(request.getMemory())
                .id(instanceId)
                .state(InstanceState.STARTING)
                .createdAt(clock.instant())
                .build();

        instances.put(instanceId, createdInstance);

        startInstance(instanceId);

        return createdInstance;
    }

    public List<Instance> list(ListInstanceRequest request) {
        return list();
    }

    public List<Instance> list() {
        return List.copyOf(instances.values());
    }

    private void startInstance(String instanceId) {
        Thread thread = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            instances.get(instanceId).setState(InstanceState.RUNNING);
        });

        thread.start();
    }
}
