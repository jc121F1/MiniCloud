package jc121f1.services.authz;

import jc121f1.model.authz.ActionDescriptor;
import jc121f1.model.authz.ServiceId;
import jc121f1.services.auth.authorization.AuthAction;

import javax.inject.Inject;

/** Authz-owned privilege boundaries; service catalog entries cannot override these. */
public final class AuthorizationRules {
    @Inject
    public AuthorizationRules() { }

    public boolean ownerOnly(ActionDescriptor action) {
        return ServiceId.AUTHZ.value().equals(action.service())
                || AuthAction.TRANSFER_OWNERSHIP.value().equals(action.value());
    }

    public boolean credentialAllowed(ActionDescriptor action) {
        return !ownerOnly(action) && !AuthAction.GENERATE_CREDENTIAL.value().equals(action.value());
    }
}
