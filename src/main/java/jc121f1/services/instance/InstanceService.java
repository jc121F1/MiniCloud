package jc121f1.services.instance;

import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.api.request.ListInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;

import java.util.List;

public interface InstanceService {
    Instance get(GetInstanceRequest request);

    Instance create(CreateInstanceRequest request);

    List<Instance> list(ListInstanceRequest request);

    Instance delete(DeleteInstanceRequest request);

    Instance stop(StopInstanceRequest request);

    Instance start(StartInstanceRequest request);
}
