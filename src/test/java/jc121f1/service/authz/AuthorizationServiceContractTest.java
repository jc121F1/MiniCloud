package jc121f1.service.authz;

import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.ResourceReference;
import jc121f1.model.authz.ServiceId;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.instance.authorization.InstanceAction;
import jc121f1.services.instance.authorization.InstanceResourceType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class AuthorizationServiceContractTest {
    @Test
    void typed_calls_use_the_same_wire_identifier_and_do_not_bypass_denials() {
        AuthenticatedSession principal = new AuthenticatedSession("a-1", "u-1", Session.SubjectType.USER);
        ResourceReference resource = ResourceReference.of(ServiceId.INSTANCE, "a-1", InstanceResourceType.INSTANCE, "i-1");
        AuthorizationDecision denied = new AuthorizationDecision(AuthorizationDecision.Outcome.DENY,
                AuthorizationDecision.Reason.NO_MATCHING_ALLOW, List.of());
        AuthorizationService service = new AuthorizationService() {
            @Override
            public AuthorizationDecision evaluate(AuthenticatedSession caller, String action, ResourceReference target) {
                Assertions.assertThat(caller).isEqualTo(principal);
                Assertions.assertThat(action).isEqualTo("instance:Start");
                Assertions.assertThat(target).isEqualTo(resource);
                return denied;
            }

            @Override
            public void authorize(AuthenticatedSession caller, String action, ResourceReference target) {
                evaluate(caller, action, target);
                throw new AuthorizationDeniedException();
            }
        };
        Assertions.assertThat(service.evaluate(principal, InstanceAction.START, resource)).isEqualTo(denied);
        Assertions.assertThatThrownBy(() -> service.authorize(principal, InstanceAction.START, resource))
                .isInstanceOf(AuthorizationDeniedException.class);
    }
}
