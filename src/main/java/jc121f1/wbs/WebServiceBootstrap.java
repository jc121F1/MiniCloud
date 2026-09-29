package jc121f1.wbs;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;

import java.util.Objects;
import java.util.function.Consumer;

/** Shared HTTP-server wiring. Services retain ownership of routes and authorization. */
public final class WebServiceBootstrap {

    private WebServiceBootstrap() {
    }

    public static Javalin create(Options options, Consumer<JavalinConfig> serviceConfiguration) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(serviceConfiguration, "serviceConfiguration");
        return Javalin.create(config -> {
            configureCommon(config, options);
            serviceConfiguration.accept(config);
        });
    }

    private static void configureCommon(JavalinConfig config, Options options) {
        config.registerPlugin(new OpenApiPlugin(plugin -> plugin.withDefinitionConfiguration((version, definition) ->
                definition.info(info -> info.title(options.openApiTitle())))));
        config.registerPlugin(new SwaggerPlugin());
        if (options.redocEnabled()) {
            config.registerPlugin(new ReDocPlugin());
        }
        config.routes.exception(Exception.class, options.exceptionMapper()::mapException);
        config.events.serverStarted(() -> {
            if (!options.disableJmDNS()) {
                options.jmDNSManager().startMdns(options.hostName(), options.port());
            }
        });
        config.events.serverStopping(() -> {
            if (!options.disableJmDNS()) {
                options.jmDNSManager().stopMdns(options.hostName());
            }
        });
    }

    public record Options(String openApiTitle, boolean redocEnabled, MiniCloudExceptionMapper exceptionMapper,
                          JmDNSManager jmDNSManager, boolean disableJmDNS, String hostName, int port) {
        public Options {
            Objects.requireNonNull(openApiTitle, "openApiTitle");
            Objects.requireNonNull(exceptionMapper, "exceptionMapper");
            Objects.requireNonNull(jmDNSManager, "jmDNSManager");
            Objects.requireNonNull(hostName, "hostName");
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
        }
    }
}
