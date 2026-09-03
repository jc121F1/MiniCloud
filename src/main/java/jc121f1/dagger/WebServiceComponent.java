package jc121f1.dagger;

import jc121f1.dagger.qualifiers.Debug;
import jc121f1.dagger.qualifiers.DisableJmDNS;
import jc121f1.dagger.qualifiers.ExposeShutdownEndpoint;
import jc121f1.wbs.JmDNSManager;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;


public interface WebServiceComponent {
    @ExposeShutdownEndpoint
    Boolean shutdownEndpoint();

    @Debug
    Boolean debug();

    @DisableJmDNS
    Boolean disableJmDNS();

    MiniCloudExceptionMapper exceptionMapper();

    JmDNSManager jmDNSManager();
}
