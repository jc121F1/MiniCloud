package jc121f1.services.instance;

import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.api.request.ListInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;

import java.util.List;

public interface InstanceService {
    Instance get(AuthenticatedSession caller, GetInstanceRequest request);

    Instance create(AuthenticatedSession caller, CreateInstanceRequest request);

    List<Instance> list(AuthenticatedSession caller, ListInstanceRequest request);

    Instance delete(AuthenticatedSession caller, DeleteInstanceRequest request);

    Instance stop(AuthenticatedSession caller, StopInstanceRequest request);

    Instance start(AuthenticatedSession caller, StartInstanceRequest request);
}
