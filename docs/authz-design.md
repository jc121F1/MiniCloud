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

The Java contract is defined in `services.authz.AuthorizationService`: the principal is the existing `AuthenticatedSession` and the target is a `ResourceReference`. Local callers pass a service-owned `ActionDefinition`; its canonical string identifies the action for transport and persistence. `evaluate` returns `AuthorizationDecision`; `authorize` returns normally only on allow and throws `AuthorizationDeniedException` on denial. Storage failures propagate separately.

`AuthorizationServiceImpl` uses the existing identity-store interfaces and `PolicyStore`, with no DynamoDB or HTTP dependencies. It rejects malformed requests and cross-account targets before reads, then requests strongly consistent account/user/credential reads via `GenericStore.get(id, true)`. Every evaluation loads current policies; stored documents are revalidated and corrupt/incompatible policy data fails as a storage error. Decisions include deduplicated, sorted matched policy IDs/revisions from the credential and creator where applicable. The evaluator also rejects deletion of the current account owner. Existing auth and instance routes do not yet invoke this evaluator.

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

Principal creation stays in `AuthService`: `createUser` creates a user principal and `generateCredential` creates a credential principal. Authz introduces no duplicate identity store or public `createPrincipal` operation. `PrincipalReference(accountId, subjectId, subjectType)` identifies attachment targets; implementations verify these against current auth state. New identities have no policy grants, except for the account owner's built-in permissions. Credential creation records immutable creator metadata. Deletion/revocation must immediately make a principal unusable for authorization even if attachment cleanup runs later; cleanup cannot restore access, and identity IDs must never be reused.

Validate documents before persistence: supported version/effect, nonempty statements and lists, registered actions, valid patterns, same-account resources, compatible action/resource types, and explicit size limits. Attachments must reference existing same-account principals and policies.

V1 validation limits are 32 statements, 32 action entries and 32 resource entries per statement, 128 ASCII letters/digits/underscore/hyphen characters per ID, and 16 KiB total UTF-8 action/resource string content (excluding JSON punctuation). Services and resource types use lowercase registry names. Each action pattern and resource must have at least one compatible partner in the statement. A service wildcard only applies to registered actions supporting that resource type; `instance:*` on `instance/*` does not grant account-scoped create/list operations. The API layer must separately bound the incoming request body before parsing.

`InstanceAction`, `AuthAction`, and `PolicyAction` define each service's operations and typed resource compatibility. `ServiceId` holds canonical service names for this deployment; full action identifiers are derived, never hand-assembled in declarations. An injected `ActionRegistry` consumes immutable `ActionDescriptor` entries and rejects malformed or duplicate definitions. Authz owns owner-only and credential restrictions separately in `AuthorizationRules`; action catalogs cannot override them. Declaring an action does not expose an endpoint or grant access. `PolicyValidationException`, `PolicyNotFoundException`, `PolicyConflictException`, and `AuthorizationStoreException` distinguish 400, 404, 409, and generic 500 failures; permission denials remain separate.

Use conditional revisions for updates and atomic attachment changes. Reject deletion of an attached policy. Authorization reads must observe completed policy updates/detachments; do not cache grants in sessions or rely on eventually consistent indexes for enforcement. Storage failures fail closed and surface as service errors. Requests already authorized may complete; revocation is not cancellation of in-flight work.

`PolicyStore` is a trusted persistence interface; the forthcoming `PolicyService` implementation must authorize callers, validate documents, and resolve principal existence before using it. `DynamoDbPolicyStore` uses the dedicated `MiniCloudAuthorizationStore` table with `pk`/`sk` keys. Policy rows use account partitions; attachment rows use account plus principal type/ID partitions. Base-table queries and point reads are strongly consistent and queries follow all pages.

The policy store extends `common.store.nosql.DynamoDbStore<PolicyRecord>`. The common layer owns Enhanced Client mapping, table initialization, composite-key access, query pagination, and transaction execution. Its CRUD transaction builders can be composed with conditional attribute updates, while ordinary update/delete now accept optional persisted-state conditions. Default CRUD semantics remain unchanged. Partial updates are restricted to stores without unique constraints; constrained stores must use the CRUD builders so unique-lock changes remain atomic. Composite-key stores currently reject unique-constraint definitions because the existing unique-lock format is partition-only.

Policy documents use the Enhanced Client's nested document mapping; there is no Authz-specific JSON persistence codec. Revision predicates, attachment counts, tombstones, and Authz error translation stay in the policy store. The unverified earlier JSON-string storage prototype is replaced, not automatically migrated.

Each policy tracks an attachment count. Attach/detach transactions conditionally change that count together with the attachment row, so duplicate requests cannot drift the count. Updates compare revisions without overwriting the count. Deletion atomically requires the expected revision and zero attachments, removes the document, and retains an ID tombstone. Tombstones are excluded from reads and prevent deleted IDs from being reused within the account. Attachment changes do not increment document revisions.

Conflicting concurrent operations either resolve idempotently or report a conflict for the caller to retry. Throttling/unavailability and malformed stored data are storage errors. Reads spanning multiple rows are not a single database snapshot: a concurrent detach followed by deletion may make an in-flight attachment read fail closed and require retry. Completed changes are visible to later evaluations. DynamoDB transactions are internal to Authz; future resource services access Authz through its API, not this table.

New-account signup establishes the first user as owner through the existing account creation flow. Ownership transfer requires the current owner and an existing same-account user, and updates ownership atomically. Reject deletion of the current owner. Lost-owner recovery is an explicit operator procedure outside the public API; no unauthenticated recovery endpoint.

## Enforcement and verification

Policy-management endpoints use this subsystem's owner checks from the start. Login, token exchange, and new-account signup are explicit authentication/bootstrap operations; other operations require declared authorization. Existing auth endpoint account checks will subsequently use the shared evaluator. Missing authorization metadata must not silently make an operation public.

Audit policy mutations and decisions using principal, account, action, resource, outcome, reason, and policy revision; never record secrets or tokens. API responses distinguish invalid authentication (401) from denied permission (403), without exposing policy details. Resource APIs may consistently conceal inaccessible resources with 404.

Completion requires tests for default deny, allow/deny precedence, exact/wildcard matching, cross-account isolation, owner recovery and transfer, credential permission intersection, creator deletion, policy replacement/detachment, concurrent management changes, and storage failures. Test management operations through their API and verify denied mutations leave storage unchanged. Instance integration starts only after this contract and its tests are complete.

## Future service decomposition

Services own their action catalogs and resource ownership data. Auth owns identities; Authz owns policies and decisions. Shared contracts can move into a small independently versioned API artifact, with service action enums in each service's contract artifact. Authz's evaluator and validator must not import compute implementations or service-owned instance enums.

Today `AuthorizationCatalogModule` assembles the deployment catalog using local enums. The registry itself consumes plain descriptors such as `{"service":"instance","operation":"Start","resourceTypes":["instance"]}`. A future deployment loader can consume approved versioned manifests instead, including services absent from this deployment's Java `ServiceId` enum. The descriptor is a data contract, not a remote registration endpoint; transport, manifest versions, and catalog distribution are not implemented yet.

Catalog publication must be controlled by deployment/service identity and restricted to the publishing service's namespace. End users and authorization requests cannot register actions or supply resource-type definitions. Unknown actions deny access. Identifiers are stable and case-sensitive: never serialize Java class names or enum ordinals, and do not rename existing wire identifiers. Adding an action can expand existing `service:*` grants; catalog changes require review with that consequence in mind. Publish a compatible catalog before rolling out callers; a stale catalog denies unknown actions.

After decomposition, authenticate both the calling service and the end-user identity. A serialized `AuthenticatedSession` supplied by a client is not proof of identity. The resource-owning service resolves ownership, requests a decision, and enforces it before side effects; Authz validates the caller's authority to assert that namespace and rechecks current principal/policy state through trusted interfaces. Do not share database access across services or silently allow requests when Authz is unavailable. Remote revocation consistency and concurrent ownership changes need explicit protocols before service extraction; the current in-process contract alone does not solve them.

## Implementation checkpoints

1. Contracts, service-owned action catalogs, policy validator, management errors, and credential creator metadata: user confirmed the checks passed, including the catalog refactor. Creator/account reassignment is rejected by normal credential-store updates, including attempts to assign an inferred creator to legacy credentials. Legacy credentials remain readable; the forthcoming evaluator must deny credentials without a creator.
2. Policy persistence using the extended common store: user confirmed the refactor's tests passed. Local tests require DynamoDB at localhost:8000 and `DynamoDbLocalAvailable=True`; Authz tests create and remove a uniquely named test table.
3. Authorization evaluator and enforcement: implemented with permission-matrix tests and a DynamoDB Local policy-change visibility test; awaiting user-run verification. Strong identity reads reuse the common store. Endpoint integration and audit emission remain later work.
4. Policy-management implementation and concurrency tests: pending.
5. Management API, audit records, and integration tests: pending.

Each checkpoint is committed before pausing for the user to run tests. Do not proceed past a checkpoint until its results are reviewed.
