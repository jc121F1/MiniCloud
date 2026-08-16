package jc121f1.services.instance;

import jc121f1.model.instance.api.CreateInstanceRequest;
import jc121f1.model.instance.api.GetInstanceRequest;
import jc121f1.model.instance.api.ListInstanceRequest;
import jc121f1.model.instance.dao.Instance;

import java.util.List;

public interface InstanceService {
    Instance get(GetInstanceRequest request);

    Instance create(CreateInstanceRequest request);

    List<Instance> list(ListInstanceRequest request);

    List<Instance> list();
}
