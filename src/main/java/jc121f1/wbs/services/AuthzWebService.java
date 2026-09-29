package jc121f1.wbs.services;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import io.javalin.json.JavalinJackson;
import jc121f1.dagger.authz.AuthzWebServiceComponent;
import jc121f1.wbs.WebService;
import jc121f1.wbs.WebServiceBootstrap;

import javax.inject.Inject;
import java.util.Map;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

/** Separate Authz HTTP boundary. Identity stores remain local until service extraction. */
public final class AuthzWebService extends WebService {
    private static final String HOSTNAME = "authz";
    private static final int PORT = 7072;
    private static final long MAX_REQUEST_BYTES = 64 * 1024;
    private final AuthzWebServiceComponent component;

    @Inject
    public AuthzWebService(AuthzWebServiceComponent component) {
        super(component.jmDNSManager());
        this.component = component;
    }

    @Override
    protected int getPort() {
        return PORT;
    }

    @Override
    public Javalin create() {
        var handlers = component.policyHandlers();
        boolean disableJmDNS = component.disableJmDNS();
        return WebServiceBootstrap.create(new WebServiceBootstrap.Options(
                "MiniCloud Authorization", false, component.exceptionMapper(), component.jmDNSManager(),
                disableJmDNS, HOSTNAME, PORT), config -> {
            config.http.maxRequestSize = MAX_REQUEST_BYTES;
            config.http.strictContentTypes = true;
            config.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
                mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
                mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            }));
            config.routes.exception(HttpResponseException.class, (error, ctx) -> ctx.status(error.getStatus())
                    .json(Map.of("statusCode", error.getStatus(), "message", "HTTP request rejected")));
            // Authentication is mandatory even if a future route omits authorization metadata.
            config.routes.beforeMatched(component.authenticateHandler());
            config.routes.apiBuilder(() -> path("policies", () -> {
                get(handlers::list);
                post("create", handlers::create);
                post("describe", handlers::get);
                post("update", handlers::update);
                post("delete", handlers::delete);
                post("attach", handlers::attach);
                post("detach", handlers::detach);
                post("attachments/list", handlers::listAttached);
            }));
        });
    }
}
