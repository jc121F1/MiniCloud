package jc121f1.model.authz;

/** Implemented by service-owned resource enums; never serialize enum ordinals. */
public interface ResourceType {
    String value();
}
