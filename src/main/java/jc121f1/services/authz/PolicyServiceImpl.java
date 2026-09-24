package jc121f1.services.authz;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.ResourceReference;
import jc121f1.model.authz.ServiceId;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.authorization.PolicyAction;
import jc121f1.services.authz.authorization.PolicyResourceType;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.exceptions.PolicyValidationException;
import jc121f1.services.authz.store.PolicyStore;

import javax.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Owner-only policy administration over the existing identity and policy stores. */
public final class PolicyServiceImpl implements PolicyService {
    private final AuthorizationService authorization;
    private final PolicyStore policies;
    private final PolicyValidator validator;
    private final UserStore users;
    private final CredentialStore credentials;

    @Inject
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected services and stores are intentionally shared.")
    public PolicyServiceImpl(AuthorizationService authorization, PolicyStore policies, PolicyValidator validator,
                             UserStore users, CredentialStore credentials) {
        this.authorization = authorization;
        this.policies = policies;
        this.validator = validator;
        this.users = users;
        this.credentials = credentials;
    }

    @Override
    public Policy createPolicy(AuthenticatedSession caller, PolicyDocument document) {
        Objects.requireNonNull(document, "document");
        authorizeAccount(caller, PolicyAction.CREATE_POLICY);
        validator.validate(caller.accountId(), document);
        Policy policy = new Policy("p-" + UUID.randomUUID(), caller.accountId(), 1, document);
        return await(() -> policies.create(policy));
    }

    @Override
    public Policy getPolicy(AuthenticatedSession caller, String policyId) {
        authorizePolicy(caller, PolicyAction.GET_POLICY, policyId);
        return await(() -> policies.get(caller.accountId(), policyId))
                .orElseThrow(() -> new PolicyNotFoundException("Policy not found"));
    }

    @Override
    public List<Policy> listPolicies(AuthenticatedSession caller) {
        authorizeAccount(caller, PolicyAction.LIST_POLICIES);
        return List.copyOf(await(() -> policies.list(caller.accountId())));
    }

    @Override
    public Policy updatePolicy(AuthenticatedSession caller, String policyId, long expectedRevision,
                               PolicyDocument document) {
        Objects.requireNonNull(document, "document");
        authorizePolicy(caller, PolicyAction.UPDATE_POLICY, policyId);
        validateRevision(expectedRevision);
        validator.validate(caller.accountId(), document);
        return await(() -> policies.update(caller.accountId(), policyId, expectedRevision, document));
    }

    @Override
    public void deletePolicy(AuthenticatedSession caller, String policyId, long expectedRevision) {
        authorizePolicy(caller, PolicyAction.DELETE_POLICY, policyId);
        validateRevision(expectedRevision);
        await(() -> policies.delete(caller.accountId(), policyId, expectedRevision));
    }

    @Override
    public void attachPolicy(AuthenticatedSession caller, String policyId, long expectedRevision,
                             PrincipalReference principal) {
        Objects.requireNonNull(principal, "principal");
        authorizePolicy(caller, PolicyAction.ATTACH_POLICY, policyId);
        validateRevision(expectedRevision);
        validatePrincipal(caller, principal);
        requirePrincipal(principal, true);
        await(() -> policies.attach(caller.accountId(), policyId, expectedRevision, principal));
    }

    @Override
    public void detachPolicy(AuthenticatedSession caller, String policyId, PrincipalReference principal) {
        Objects.requireNonNull(principal, "principal");
        authorizePolicy(caller, PolicyAction.DETACH_POLICY, policyId);
        validatePrincipal(caller, principal);
        // The store checks the policy even when the attachment is already absent.
        await(() -> policies.detach(caller.accountId(), policyId, principal));
    }

    @Override
    public List<Policy> listAttachedPolicies(AuthenticatedSession caller, PrincipalReference principal) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(principal, "principal");
        validatePrincipal(caller, principal);
        PolicyResourceType type = principal.subjectType() == Session.SubjectType.USER
                ? PolicyResourceType.USER : PolicyResourceType.CREDENTIAL;
        authorize(caller, PolicyAction.LIST_ATTACHED_POLICIES, type, principal.subjectId());
        requirePrincipal(principal, false);
        return List.copyOf(await(() -> policies.listAttached(principal)));
    }

    private void authorizeAccount(AuthenticatedSession caller, PolicyAction action) {
        Objects.requireNonNull(caller, "caller");
        authorize(caller, action, PolicyResourceType.ACCOUNT, caller.accountId());
    }

    private void authorizePolicy(AuthenticatedSession caller, PolicyAction action, String policyId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(policyId, "policyId");
        if (!PolicyValidator.isValidIdentifier(policyId)) {
            throw new PolicyValidationException("Invalid policy ID");
        }
        authorize(caller, action, PolicyResourceType.POLICY, policyId);
    }

    private void authorize(AuthenticatedSession caller, PolicyAction action, PolicyResourceType type, String id) {
        authorization.authorize(caller, action, ResourceReference.of(ServiceId.AUTHZ, caller.accountId(), type, id));
    }

    private static void validateRevision(long revision) {
        if (revision < 1) {
            throw new PolicyValidationException("Expected revision must be positive");
        }
    }

    private static void validatePrincipal(AuthenticatedSession caller, PrincipalReference principal) {
        if (!PolicyValidator.isValidIdentifier(principal.accountId())
                || !PolicyValidator.isValidIdentifier(principal.subjectId()) || principal.subjectType() == null) {
            throw new PolicyValidationException("Invalid principal reference");
        }
        if (!principal.accountId().equals(caller.accountId())) {
            throw new AuthorizationDeniedException();
        }
    }

    private void requirePrincipal(PrincipalReference principal, boolean usable) {
        if (principal.subjectType() == Session.SubjectType.USER) {
            requireUser(principal.accountId(), principal.subjectId());
            return;
        }
        var credential = await(() -> credentials.get(principal.subjectId(), true)).orElse(null);
        if (credential == null || !principal.subjectId().equals(credential.credentialId())
                || !principal.accountId().equals(credential.accountId())) {
            throw new PolicyNotFoundException("Principal not found");
        }
        if (usable) {
            if (!credential.isUsable() || !PolicyValidator.isValidIdentifier(credential.createdByUserId())) {
                throw new PolicyValidationException("Credential is not usable");
            }
            requireUser(principal.accountId(), credential.createdByUserId());
        }
    }

    private void requireUser(String accountId, String userId) {
        var user = await(() -> users.get(userId, true)).orElse(null);
        if (user == null || !userId.equals(user.userId()) || !accountId.equals(user.accountId())) {
            throw new PolicyNotFoundException("Principal not found");
        }
    }

    private static <T> T await(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get().join();
        } catch (RuntimeException error) {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof PolicyNotFoundException missing) {
                throw missing;
            }
            if (cause instanceof PolicyConflictException conflict) {
                throw conflict;
            }
            if (cause instanceof PolicyValidationException invalid) {
                throw invalid;
            }
            if (cause instanceof AuthorizationStoreException unavailable) {
                throw unavailable;
            }
            throw new AuthorizationStoreException("Unable to access authorization storage", cause);
        }
    }
}
