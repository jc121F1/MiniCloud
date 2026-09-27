package jc121f1;

import jc121f1.dagger.auth.DaggerAuthWebServiceComponent;
import jc121f1.wbs.services.AuthWebService;
import jc121f1.dagger.authz.DaggerAuthzWebServiceComponent;
import jc121f1.wbs.services.AuthzWebService;
import jc121f1.dagger.instance.DaggerInstanceWebServiceComponent;
import jc121f1.wbs.services.InstanceWebService;
import jc121f1.runtime.ApplicationRuntime;

public class Main {
    static void main(String[] args) {
        ApplicationRuntime runtime = new ApplicationRuntime();
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "minicloud-shutdown"));
        try {
            var instance = DaggerInstanceWebServiceComponent.create();
            runtime.startService(instance.runtimeResources(), () -> {
                instance.storeInitializer().initialize();
                instance.initializableInstanceStore().initialize().join();
                instance.computeBackend().initialize();
                instance.managedInstanceService().initialize();
            }, () -> new InstanceWebService(instance));
            var auth = DaggerAuthWebServiceComponent.create();
            runtime.startService(auth.runtimeResources(), () -> auth.storeInitializer().initialize(),
                    () -> new AuthWebService(auth));
            var authz = DaggerAuthzWebServiceComponent.create();
            runtime.startService(authz.runtimeResources(), () -> authz.storeInitializer().initialize(),
                    () -> new AuthzWebService(authz));
        } catch (RuntimeException | Error failure) {
            runtime.close();
            throw failure;
        }
    }
}
