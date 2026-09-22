package jc121f1.services.authz;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.ActionDescriptor;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.AuthorizationDecision.Outcome;
import jc121f1.model.authz.AuthorizationDecision.PolicyReference;
import jc121f1.model.authz.AuthorizationDecision.Reason;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.store.PolicyStore;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Evaluates current trusted state; contains no HTTP, resource-service, or DynamoDB dependency. */
public final class AuthorizationServiceImpl implements AuthorizationService {
    private final AccountStore accounts;
    private final UserStore users;
    private final CredentialStore credentials;
    private final PolicyStore policies;
    private final ActionRegistry registry;
    private final PolicyValidator validator;
    private final AuthorizationRules rules;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "policies and rules are injected dependency and is intentionally shared."
    )
    @Inject
    public AuthorizationServiceImpl(AccountStore accounts, UserStore users, CredentialStore credentials,
                                    PolicyStore policies, ActionRegistry registry, PolicyValidator validator,
                                    AuthorizationRules rules) {
        this.accounts = accounts;
        this.users = users;
        this.credentials = credentials;
        this.policies = policies;
        this.registry = registry;
        this.validator = validator;
        this.rules = rules;
    }

    @Override
    public AuthorizationDecision evaluate(AuthenticatedSession principal, String actionName, ResourceReference resource) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(actionName, "action");
        Objects.requireNonNull(resource, "resource");
        if (!isValidPrincipal(principal)) {
            return deny(Reason.INVALID_PRINCIPAL);
        }
        ActionDescriptor action = registry.find(actionName).orElse(null);
        if (action == null) {
            return deny(Reason.INVALID_ACTION);
        }
        if (!validator.isValidTarget(action, resource)) {
            return deny(Reason.INVALID_RESOURCE);
        }
        if (!principal.accountId().equals(resource.accountId())) {
            return deny(Reason.CROSS_ACCOUNT);
        }
        Account account = read(() -> accounts.get(principal.accountId(), true)).orElse(null);
        if (account == null || account.status() != Account.AccountStatus.ACTIVE) {
            return deny(Reason.INACTIVE_ACCOUNT);
        }
        if (!principal.accountId().equals(account.accountId())) {
            return deny(Reason.INVALID_PRINCIPAL);
        }
        return switch (principal.subjectType()) {
            case USER -> evaluateUser(principal, account, action, resource);
            case CREDENTIAL -> evaluateCredential(principal, account, action, resource);
            default -> deny(Reason.INVALID_PRINCIPAL);
        };
    }

    private AuthorizationDecision evaluateUser(AuthenticatedSession principal, Account account,
                                               ActionDescriptor action, ResourceReference resource) {
        User user = read(() -> users.get(principal.subjectId(), true)).orElse(null);
        if (!matches(user, principal.subjectId(), account.accountId())) {
            return deny(Reason.INVALID_PRINCIPAL);
        }
        if (rules.deletesOwner(action, resource, account.ownerId())) {
            return deny(Reason.ACCOUNT_OWNER_PROTECTED);
        }
        boolean owner = user.userId().equals(account.ownerId());
        if (rules.ownerOnly(action)) {
            return owner ? decision(Outcome.ALLOW, Reason.OWNER_RECOVERY_ALLOW, List.of()) : deny(Reason.OWNER_REQUIRED);
        }
        return evaluatePolicies(reference(principal), action, resource, owner);
    }

    private AuthorizationDecision evaluateCredential(AuthenticatedSession principal, Account account,
                                                     ActionDescriptor action, ResourceReference resource) {
        var credential = read(() -> credentials.get(principal.subjectId(), true)).orElse(null);
        if (!isUsableCredential(credential, principal)) {
            return deny(Reason.INVALID_PRINCIPAL);
        }
        if (!PolicyValidator.isValidIdentifier(credential.createdByUserId())) {
            return deny(Reason.CREDENTIAL_CREATOR_MISSING);
        }
        User creator = read(() -> users.get(credential.createdByUserId(), true)).orElse(null);
        if (!matches(creator, credential.createdByUserId(), account.accountId())) {
            return deny(Reason.CREDENTIAL_CREATOR_MISSING);
        }
        if (!rules.credentialAllowed(action)) {
            return deny(Reason.CREDENTIAL_OPERATION_FORBIDDEN);
        }
        if (rules.deletesOwner(action, resource, account.ownerId())) {
            return deny(Reason.ACCOUNT_OWNER_PROTECTED);
        }
        return evaluateCredentialPolicies(principal, creator, account, action, resource);
    }

    private AuthorizationDecision evaluateCredentialPolicies(AuthenticatedSession principal, User creator, Account account,
                                                             ActionDescriptor action, ResourceReference resource) {
        AuthorizationDecision own = evaluatePolicies(reference(principal), action, resource, false);
        if (own.outcome() == Outcome.DENY) {
            return own;
        }
        AuthorizationDecision ceiling = evaluatePolicies(new PrincipalReference(account.accountId(), creator.userId(),
                Session.SubjectType.USER), action, resource, creator.userId().equals(account.ownerId()));
        List<PolicyReference> matched = Stream.concat(own.matchedPolicies().stream(), ceiling.matchedPolicies().stream()).toList();
        return ceiling.outcome() == Outcome.ALLOW ? decision(Outcome.ALLOW, Reason.POLICY_ALLOW, matched)
                : decision(Outcome.DENY, Reason.CREDENTIAL_CREATOR_DENIED, matched);
    }

    @Override
    public void authorize(AuthenticatedSession principal, String action, ResourceReference resource) {
        if (evaluate(principal, action, resource).outcome() != Outcome.ALLOW) {
            throw new AuthorizationDeniedException();
        }
    }

    private AuthorizationDecision evaluatePolicies(PrincipalReference principal, ActionDescriptor action,
                                                     ResourceReference resource, boolean owner) {
        List<Policy> attached = read(() -> policies.listAttached(principal));
        boolean allowed = false;
        boolean denied = false;
        List<PolicyReference> matched = new ArrayList<>();
        for (Policy policy : attached) {
            validateStoredPolicy(policy, principal.accountId());
            for (PolicyDocument.Statement statement : policy.document().statements()) {
                boolean matchesAction = statement.actions().stream().anyMatch(pattern -> pattern.equals(action.value())
                        || pattern.equals(action.service() + ":*"));
                if (matchesAction && statement.resources().stream().anyMatch(pattern -> matchesResource(pattern, resource))) {
                    matched.add(new PolicyReference(policy.policyId(), policy.revision()));
                    if (statement.effect() == PolicyDocument.Effect.DENY) {
                        denied = true;
                    } else {
                        allowed = true;
                    }
                }
            }
        }
        if (denied) {
            return decision(Outcome.DENY, Reason.EXPLICIT_DENY, matched);
        }
        if (owner) {
            return decision(Outcome.ALLOW, Reason.OWNER_ALLOW, matched);
        }
        return allowed ? decision(Outcome.ALLOW, Reason.POLICY_ALLOW, matched)
                : decision(Outcome.DENY, Reason.NO_MATCHING_ALLOW, matched);
    }

    private boolean matchesResource(String pattern, ResourceReference resource) {
        ResourceReference parsed = validator.parseResourcePattern(pattern);
        return parsed.service().equals(resource.service()) && parsed.accountId().equals(resource.accountId())
                && parsed.resourceType().equals(resource.resourceType())
                && (parsed.resourceId().equals("*") || parsed.resourceId().equals(resource.resourceId()));
    }

    private void validateStoredPolicy(Policy policy, String accountId) {
        try {
            if (policy == null || !PolicyValidator.isValidIdentifier(policy.policyId())
                    || !accountId.equals(policy.accountId()) || policy.revision() < 1) {
                throw new IllegalArgumentException("Invalid policy metadata");
            }
            validator.validate(accountId, policy.document());
        } catch (RuntimeException error) {
            throw new AuthorizationStoreException("Invalid stored authorization policy", error);
        }
    }

    private static boolean matches(User user, String userId, String accountId) {
        return user != null && userId.equals(user.userId()) && accountId.equals(user.accountId());
    }

    private static boolean isValidPrincipal(AuthenticatedSession principal) {
        return PolicyValidator.isValidIdentifier(principal.accountId())
                && PolicyValidator.isValidIdentifier(principal.subjectId())
                && principal.subjectType() != null;
    }

    private static boolean isUsableCredential(Credential credential, AuthenticatedSession principal) {
        return credential != null
                && credential.isUsable()
                && principal.subjectId().equals(credential.credentialId())
                && principal.accountId().equals(credential.accountId());
    }

    private static PrincipalReference reference(AuthenticatedSession principal) {
        return new PrincipalReference(principal.accountId(), principal.subjectId(), principal.subjectType());
    }

    private static AuthorizationDecision deny(Reason reason) {
        return decision(Outcome.DENY, reason, List.of());
    }

    private static AuthorizationDecision decision(Outcome outcome, Reason reason, List<PolicyReference> matched) {
        return new AuthorizationDecision(outcome, reason, matched.stream().distinct()
                .sorted(Comparator.comparing(PolicyReference::policyId).thenComparingLong(PolicyReference::revision)).toList());
    }

    private static <T> T read(Supplier<CompletableFuture<T>> operation) {
        try {
            return Objects.requireNonNull(operation.get().join(), "Store returned null");
        } catch (AuthorizationStoreException error) {
            throw error;
        } catch (RuntimeException error) {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof AuthorizationStoreException storeError) {
                throw storeError;
            }
            throw new AuthorizationStoreException("Unable to read current authorization state", cause);
        }
    }
}
