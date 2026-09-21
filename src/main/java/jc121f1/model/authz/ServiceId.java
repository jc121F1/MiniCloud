package jc121f1.model.authz;

/** Canonical names for services in this deployment; wire catalogs use strings. */
public enum ServiceId {
    INSTANCE("instance"),
    AUTH("auth"),
    AUTHZ("authz");

    private final String value;

    ServiceId(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
