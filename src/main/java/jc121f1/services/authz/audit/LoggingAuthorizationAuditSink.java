package jc121f1.services.authz.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/** Structured JSON payloads on a dedicated logger; deployment owns collection and retention. */
public final class LoggingAuthorizationAuditSink implements AuthorizationAuditSink {
    private static final Logger LOG = LoggerFactory.getLogger("jc121f1.audit.authorization");
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public LoggingAuthorizationAuditSink() {
    }

    @Override
    public void record(AuthorizationAuditEvent event) {
        try {
            LOG.info("{}", mapper.writeValueAsString(event));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize authorization audit event");
        }
    }
}
