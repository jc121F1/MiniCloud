package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.model.authz.ActionDefinition;
import jc121f1.model.authz.ActionDescriptor;
import jc121f1.services.auth.authorization.AuthAction;
import jc121f1.services.authz.ActionRegistry;
import jc121f1.services.authz.authorization.PolicyAction;
import jc121f1.services.instance.authorization.InstanceAction;

import javax.inject.Singleton;
import java.util.stream.Stream;

/** Composition root for this deployment; Authz itself does not import instance actions. */
@Module
public final class AuthorizationCatalogModule {
    private AuthorizationCatalogModule() { }

    @Provides
    @Singleton
    public static ActionRegistry actionRegistry() {
        return new ActionRegistry(Stream.<ActionDefinition[]>of(
                        InstanceAction.values(), AuthAction.values(), PolicyAction.values())
                .flatMap(Stream::of)
                .map(ActionDescriptor::from).toList());
    }
}
