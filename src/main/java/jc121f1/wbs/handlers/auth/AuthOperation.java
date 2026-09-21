package jc121f1.wbs.handlers.auth;

import io.javalin.security.RouteRole;

public enum AuthOperation implements RouteRole {
    PUBLIC,
    CREATE_USER,
    DESCRIBE_USER,
    DELETE_USER,
    INVALIDATE_CREDENTIAL
}
