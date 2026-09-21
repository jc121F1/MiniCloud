# Authorization design

Status: proposed. Implement and verify Authz before integrating instance operations.

## Scope and existing foundation

Authn establishes identity; Authz decides whether that identity may perform an action on a resource. MiniCloud already has accounts with an `ownerId`, users, credentials, and `AuthenticatedSession(accountId, subjectId, subjectType)`. Current account checks live in `AuthAuthorizationHandler`; they are not a general permission system.

V1 delivers policy storage, management operations, a framework-independent evaluator, and authorization tests. Instance integration is a later milestone. Roles, groups, cross-account delegation, resource-based policies, and conditional expressions are deferred.

## Model

| Concept | V1 contract |
| --- | --- |
| Principal | Authenticated user or credential, resolved from trusted identity state. |
| Action | Registered, case-sensitive `service:Operation`, such as `instance:Start`. Unknown actions are rejected. |
| Resource | Typed reference containing service, account ID, resource type, and ID. Ownership comes from trusted storage, never a caller's ownership claim. |
| Policy | Account-owned document with an ID, revision, and allow/deny statements. |
| Attachment | Connects a policy to a user or credential in the same account. |

Resource strings serialize as `mc:<service>:<accountId>:<type>/<id>`. For creation and collection operations, use an explicit account resource such as `mc:instance:a-123:account/a-123`; authorization does not require a resource to exist before creation.

Example policy allowing describe/start/stop for a specific instance:

```json
{
  "version": 1,
  "statements": [
    {
      "effect": "ALLOW",
      "actions": ["instance:Describe", "instance:Start", "instance:Stop"],
      "resources": ["mc:instance:a-123:instance/i-456"]
    },
    {
      "effect": "DENY",
      "actions": ["instance:Delete"],
      "resources": ["mc:instance:a-123:instance/*"]
    }
  ]
}
```

Action patterns permit an exact action or `service:*`. Resource patterns permit an exact reference or `*` as the entire final ID segment. No partial globs, regexes, account wildcards, or implicit matching between account and instance resources. A statement matches when both an action and a resource entry match; entries within each list are alternatives.

## Evaluation contract

`AuthorizationService.evaluate(principal, action, resource)` returns an allow/deny decision, a stable internal reason, and matched policy IDs/revisions. It accepts no HTTP context. A separate enforcement method throws on denial; callers must enforce before performing protected work.

The Java contract is defined in `services.authz.AuthorizationService`: the principal is the existing `AuthenticatedSession`, the action is an exact registered string, and the target is a `ResourceReference`. `evaluate` returns `AuthorizationDecision`; `authorize` returns normally only on allow and throws `AuthorizationDeniedException` on denial. Storage failures propagate separately.

Evaluate in this order:

1. Resolve current principal/account state. Deny deleted users, revoked credentials, inactive accounts, malformed references, and cross-account requests.
2. Apply the owner-only rules below. Ordinary policy grants cannot override them.
3. Load applicable policies. Any matching explicit deny wins; otherwise require a matching allow. No attachments or no matching allow means deny.
4. For credentials, also require the creator's current effective permissions to allow the same action and resource.

Add immutable `createdByUserId` to credentials. A credential's permissions are the intersection of its own policies and its creator's current permissions; creation alone grants nothing. Deleting the creator disables its credentials. Credentials lacking creator metadata remain denied until explicitly migrated. Credential principals cannot create credentials or manage authorization.

Account owners receive a built-in same-account allow for ordinary actions, still subject to explicit denies. Policy management and ownership transfer are reserved to the authenticated owner user and remain available for recovery despite attached policy denies. This exception never bypasses inactive-account or cross-account checks and never extends to the owner's credentials.

## Policy lifecycle and privilege boundaries

Only the owner user can create, replace, delete, attach, or detach policies in V1. Other users cannot grant themselves permissions even if a policy mentions these actions. This deliberately postpones delegated policy administration and policy-subset reasoning.

`PolicyService` defines create/get/list/update/delete and attach/detach/list-attached operations. Reads also require the owner in V1. Every method takes an authenticated caller and enforces ownership internally. Updates, deletes, and attaches carry an expected policy revision to detect stale management requests. Attachments follow subsequent policy revisions, rather than pinning a revision.

Principal creation stays in `AuthService`: `createUser` creates a user principal and `generateCredential` creates a credential principal. Authz introduces no duplicate identity store or public `createPrincipal` operation. `PrincipalReference(accountId, subjectId, subjectType)` identifies attachment targets; implementations verify these against current auth state. New identities have no policy grants, except for the account owner's built-in permissions. Credential creation still needs the proposed immutable creator metadata. Deletion/revocation immediately makes a principal unusable for authorization even if attachment cleanup runs later; cleanup cannot restore access, and identity IDs must never be reused.

Validate documents before persistence: supported version/effect, nonempty statements and lists, registered actions, valid patterns, same-account resources, compatible action/resource types, and explicit size limits. Attachments must reference existing same-account principals and policies.

V1 validation limits are 32 statements, 32 action entries and 32 resource entries per statement, 128 ASCII letters/digits/underscore/hyphen characters per ID, and 16 KiB total UTF-8 action/resource string content (excluding JSON punctuation). Services and resource types use lowercase registry names. Each action pattern and resource must have at least one compatible partner in the statement. A service wildcard only applies to registered actions supporting that resource type; `instance:*` on `instance/*` does not grant account-scoped create/list operations. The API layer must separately bound the incoming request body before parsing.

`AuthorizationAction` is the closed V1 registry for instance, auth, and policy-management actions. It records resource compatibility, owner-only operations, and credential restrictions. Declaring an action does not expose an endpoint or grant access. `PolicyValidationException`, `PolicyNotFoundException`, `PolicyConflictException`, and `AuthorizationStoreException` distinguish 400, 404, 409, and generic 500 failures; permission denials remain separate.

Use conditional revisions for updates and atomic attachment changes. Reject deletion of an attached policy. Authorization reads must observe completed policy updates/detachments; do not cache grants in sessions or rely on eventually consistent indexes for enforcement. Storage failures fail closed and surface as service errors. Requests already authorized may complete; revocation is not cancellation of in-flight work.

New-account signup establishes the first user as owner through the existing account creation flow. Ownership transfer requires the current owner and an existing same-account user, and updates ownership atomically. Reject deletion of the current owner. Lost-owner recovery is an explicit operator procedure outside the public API; no unauthenticated recovery endpoint.

## Enforcement and verification

Policy-management endpoints use this subsystem's owner checks from the start. Login, token exchange, and new-account signup are explicit authentication/bootstrap operations; other operations require declared authorization. Existing auth endpoint account checks will subsequently use the shared evaluator. Missing authorization metadata must not silently make an operation public.

Audit policy mutations and decisions using principal, account, action, resource, outcome, reason, and policy revision; never record secrets or tokens. API responses distinguish invalid authentication (401) from denied permission (403), without exposing policy details. Resource APIs may consistently conceal inaccessible resources with 404.

Completion requires tests for default deny, allow/deny precedence, exact/wildcard matching, cross-account isolation, owner recovery and transfer, credential permission intersection, creator deletion, policy replacement/detachment, concurrent management changes, and storage failures. Test management operations through their API and verify denied mutations leave storage unchanged. Instance integration starts only after this contract and its tests are complete.

## Implementation checkpoints

1. Contracts, action registry, policy validator, management errors, and credential creator metadata: implemented with tests; awaiting user-run verification. Creator/account reassignment is rejected by normal credential-store updates, including attempts to assign an inferred creator to legacy credentials. Legacy credentials remain readable; the forthcoming evaluator must deny credentials without a creator.
2. Policy persistence with atomic revisions/attachments and consistent reads: pending.
3. Authorization evaluator and enforcement tests: pending.
4. Policy-management implementation and concurrency tests: pending.
5. Management API, audit records, and integration tests: pending.

Each checkpoint is committed before pausing for the user to run tests. Do not proceed past a checkpoint until its results are reviewed.
