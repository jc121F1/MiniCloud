package jc121f1.services.authz.audit;

/** Destination for internal audit metadata. Implementations must not change authorization state. */
public interface AuthorizationAuditSink {
    void record(AuthorizationAuditEvent event);
}
