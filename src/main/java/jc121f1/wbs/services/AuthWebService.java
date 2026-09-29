package jc121f1.wbs.services;

import io.javalin.Javalin;
import jc121f1.dagger.auth.AuthWebServiceComponent;
import jc121f1.wbs.WebService;
import jc121f1.wbs.WebServiceBootstrap;
import jc121f1.wbs.handlers.auth.AuthOperation;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;

import javax.inject.Inject;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class AuthWebService extends WebService {
    private static final String HOSTNAME = "auth";
    private static final int PORT = 7071;
    private final AuthWebServiceComponent component;

    @Inject
    public AuthWebService(AuthWebServiceComponent component) {
        super(component.jmDNSManager());
        this.component = component;
    }

    @Override
    protected int getPort() {
        return PORT;
    }

    @Override
    public Javalin create() {
        Boolean disableJmDNS = component.disableJmDNS();
        MiniCloudExceptionMapper exceptionMapper = component.exceptionMapper();
        return WebServiceBootstrap.create(new WebServiceBootstrap.Options(
                "OpenAPI", true, exceptionMapper, component.jmDNSManager(), disableJmDNS, HOSTNAME, PORT), config -> {
            config.routes.beforeMatched(component.authAuthorizationHandler());
            config.routes.apiBuilder(() -> {
                get(component.rootHandler());
                path("users", () -> {
                    post("create", component.createUserHandler(), AuthOperation.CREATE_USER);
                    post("describe", component.getUserHandler(), AuthOperation.DESCRIBE_USER);
                    post("delete", component.deleteUserHandler(), AuthOperation.DELETE_USER);
                    post("transfer-ownership", component.transferOwnershipHandler(), AuthOperation.TRANSFER_OWNERSHIP);
                    post("login", component.loginHandler());
                });
                path("credentials", () -> {
                    post("generate", component.generateCredentialHandler());
                    post("exchange", component.exchangeServiceCredentialHandler());
                    post("invalidate", component.invalidateCredentialHandler(), AuthOperation.INVALIDATE_CREDENTIAL);
                });
            });
        });
    }
}
