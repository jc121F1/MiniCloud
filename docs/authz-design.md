# Authorization design

Status: Authz foundation checkpoints 1–5, instance integration checkpoints 1–2, ownership transfer, and the complete HTTP lifecycle were verified by user-run tests. The user also confirmed that `checkstyleMain`, `checkstyleTest`, `spotbugsMain`, and `spotbugsTest` pass after the lifecycle changes. Codex has not run tests or Gradle checks.

Ownership-transfer checkpoint: after an earlier pass report, the user reported a failure in `OwnershipTransferTest.rejects_credential_and_member_without_writing`. The credential fixture lacked a strongly consistent store response, so the evaluator reported a storage error before the intended credential denial. The fixture was corrected in `d36fc64`. The user confirmed that the corrected `OwnershipTransferTest`, `checkstyleTest`, and `spotbugsTest` pass. The user has also confirmed the broader HTTP authorization lifecycle tests pass.

## Scope and existing foundation

Authn establishes identity; Authz decides whether that identity may perform an action on a resource. MiniCloud already has accounts with an `ownerId`, users, credentials, and `AuthenticatedSession(accountId, subjectId, subjectType)`. Current account checks live in `AuthAuthorizationHandler`; they are not a general permission system.

V1 delivers policy storage, management operations, a framework-independent evaluator, authorization tests, and instance-service enforcement. Roles, groups, cross-account delegation, resource-based policies, and conditional expressions are deferred.

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

`AuthorizationServiceImpl` uses the existing identity-store interfaces and `PolicyStore`, with no DynamoDB or HTTP dependencies. It rejects malformed requests and cross-account targets before reads, then requests strongly consistent account/user/credential reads via `GenericStore.get(id, true)`. Every evaluation loads current policies; stored documents are revalidated and corrupt/incompatible policy data fails as a storage error. Decisions include deduplicated, sorted matched policy IDs/revisions from the credential and creator where applicable. The evaluator also rejects deletion of the current account owner. Instance and protected auth routes now enforce through this evaluator.

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

`PolicyServiceImpl` enforces owner authorization on every operation through the evaluator, scopes policy storage to the caller's account, validates documents, and checks attachment targets with strongly consistent identity reads. Attaching credentials requires usable credentials and an existing same-account creator; listing their attachments remains available after revocation. Detach skips target existence checks so owners can clean up deleted identities. Identity validation and policy mutation are not a cross-store transaction: deletion/revocation during attachment can leave a dormant attachment, but the evaluator's fresh identity checks prevent it from granting access. Deployment wrappers record audit events; the HTTP adapter calls those wrappers.

`PolicyStore` is a trusted persistence interface behind policy management. `DynamoDbPolicyStore` uses the dedicated `MiniCloudAuthorizationStore` table with `pk`/`sk` keys. Policy rows use account partitions; attachment rows use account plus principal type/ID partitions. Base-table queries and point reads are strongly consistent and queries follow all pages.

The policy store extends `common.store.nosql.DynamoDbStore<PolicyRecord>`. The common layer owns Enhanced Client mapping, table initialization, composite-key access, query pagination, and transaction execution. Its CRUD transaction builders can be composed with conditional attribute updates, while ordinary update/delete now accept optional persisted-state conditions. Default CRUD semantics remain unchanged. Partial updates are restricted to stores without unique constraints; constrained stores must use the CRUD builders so unique-lock changes remain atomic. Composite-key stores currently reject unique-constraint definitions because the existing unique-lock format is partition-only.

Policy documents use the Enhanced Client's nested document mapping; there is no Authz-specific JSON persistence codec. Revision predicates, attachment counts, tombstones, and Authz error translation stay in the policy store. The unverified earlier JSON-string storage prototype is replaced, not automatically migrated.

Each policy tracks an attachment count. Attach/detach transactions conditionally change that count together with the attachment row, so duplicate requests cannot drift the count. Updates compare revisions without overwriting the count. Deletion atomically requires the expected revision and zero attachments, removes the document, and retains an ID tombstone. Tombstones are excluded from reads and prevent deleted IDs from being reused within the account. Attachment changes do not increment document revisions.

Conflicting concurrent operations either resolve idempotently or report a conflict for the caller to retry. Throttling/unavailability and malformed stored data are storage errors. Reads spanning multiple rows are not a single database snapshot: a concurrent detach followed by deletion may make an in-flight attachment read fail closed and require retry. Completed changes are visible to later evaluations. DynamoDB transactions are internal to Authz; future resource services access Authz through its API, not this table.

New-account signup establishes the first user as owner through the existing account creation flow. Ownership transfer requires the current owner and an existing same-account user, and updates ownership atomically. Reject deletion of the current owner. Lost-owner recovery is an explicit operator procedure outside the public API; no unauthenticated recovery endpoint.

`POST /users/transfer-ownership` takes `{"newOwnerUserId":"u-..."}` and requires a user bearer session. AuthService enforces the service-owned `AuthAction.TRANSFER_OWNERSHIP` through the audited evaluator. Only the current owner of an active account may call it; attached denies cannot remove this recovery right. Credentials and nonowners are denied. The service rechecks the owner with a strongly consistent account read after authorization, checks the proposed user with a strongly consistent identity read, then submits the observed owner to persistence. A transfer to the same owner is an idempotent, conditional no-op; it still requires current ownership and an existing user. A missing or foreign target returns 404; malformed input returns 400; stale owner or deletion races return 409. A caller may reread state and retry a conflict. Storage failures return 500 and do not report completion. A successful response contains the account with its new owner, not credentials or sessions. The audited decision and a separate identity-operation completion/failure event record the result without secrets.

The account and user rows remain in Auth-owned DynamoDB tables. The common `DynamoDbStore` transaction builder now offers a mapped-item condition check, composed with existing conditional CRUD builders. Transfer atomically checks that the account still has the owner observed during authorization and remains active, and that the proposed user still exists in that account. User deletion atomically checks that the account's current owner is different from the target while deleting the user and its unique email lock. Competing transfers and transfer/deletion races cannot leave an account owned by a deleted user. A preauthorized request may still finish if it wins the transaction; completed owner changes are visible to subsequent strong reads. These cross-table transactions are an in-process Auth persistence guarantee. Extracting Auth to another service or changing storage boundaries requires a new protocol preserving the same owner/user atomicity; shared database access from another service is not the contract.

Ownership checkpoint tests include service authorization and audit outcomes, HTTP mapping, and opt-in DynamoDB Local transaction races. Run with DynamoDB Local at localhost:8000 and `DynamoDbLocalAvailable=True` to include the persistence suite.

The user initially reported the ownership checkpoint commands passed, then reported the specific credential-fixture test failure above. After the fixture correction, the user confirmed `OwnershipTransferTest`, `checkstyleTest`, and `spotbugsTest` pass. The broader lifecycle tests below were subsequently reported as passing.

Ownership checkpoint commands from PowerShell in the repository root (with DynamoDB Local running for the second command):

```powershell
.\gradlew.bat test --tests 'jc121f1.service.auth.OwnershipTransferTest' --tests 'jc121f1.integration.AuthApiIntegrationTest' --tests 'jc121f1.service.auth.AuthServiceTest' --tests 'jc121f1.service.auth.AuthServiceFailureTest' --tests 'jc121f1.service.auth.AuthServiceLifecycleTest' --tests 'jc121f1.service.auth.AuthServiceAuthorizationTest'
$env:DynamoDbLocalAvailable='True'; .\gradlew.bat test --tests 'jc121f1.service.auth.OwnershipTransferLocalTest'; Remove-Item Env:\DynamoDbLocalAvailable
.\gradlew.bat checkstyleMain checkstyleTest spotbugsMain spotbugsTest
```

## Complete HTTP lifecycle checkpoint

`AuthorizationLifecycleEndToEndTest` starts the three real HTTP services against one isolated set of six uniquely named DynamoDB Local tables and wires a controlled in-memory compute backend. It uses the real identity, session, credential, instance, policy, and audited authorization services. The test creates unique accounts and identities, exercises authenticated policy management and credential exchange, checks default deny, action-specific grants, list and cross-account filtering, explicit deny, replacement and detachment visibility, revocation, user deletion, ownership transfer, recovery rights, creator ceiling changes, and audit secrecy. It deletes its own tables and stops its servers during cleanup. The legacy Docker end-to-end test is not part of this checkpoint.

Primary-key identity reads and generic scans now request strong consistency. New-user login still uses the user email GSI, which is eventually consistent; the test polls for that index with a bounded wait, without sleeping for a fixed duration. Policy attachment and document reads already use strongly consistent base-table operations. Requests begun after a completed detach, replacement, deletion, revocation, or transfer must see its effect. A request that passed authorization before such a change may complete; this suite does not assert cancellation of in-flight work. A scan is strongly consistent per read but is not a multi-item snapshot.

Credential exchange updates `lastUsedAt` only while the stored credential remains unrevoked. This prevents a late exchange write from restoring a credential that a concurrent request already revoked. The lifecycle suite checks the persisted condition against a stale credential value after HTTP revocation. A racing exchange can fail as a storage conflict; it cannot reenable the credential.

Run from PowerShell in the repository root with DynamoDB Local at localhost:8000:

```powershell
$env:DynamoDbLocalAvailable='True'; .\gradlew.bat test --tests 'jc121f1.e2e.AuthorizationLifecycleEndToEndTest' --tests 'jc121f1.service.auth.CredentialOwnershipTest'; Remove-Item Env:\DynamoDbLocalAvailable
.\gradlew.bat checkstyleMain checkstyleTest spotbugsMain spotbugsTest
```

The user confirmed that both the lifecycle test command and the Checkstyle/SpotBugs command above pass. No microservice extraction has begun.

## Enforcement and verification

Policy-management endpoints use this subsystem's owner checks from the start. Login, token exchange, and new-account signup are explicit authentication/bootstrap operations; other operations require declared authorization. Existing auth endpoint account checks will subsequently use the shared evaluator. Missing authorization metadata must not silently make an operation public.

Audit policy mutations and decisions using principal, account, action, resource, outcome, reason, and policy revision; never record secrets or tokens. API responses distinguish invalid authentication (401) from denied permission (403), without exposing policy details. Resource APIs may consistently conceal inaccessible resources with 404.

`AuthorizationModule` binds the service interfaces to `AuditedAuthorizationService` and `AuditedPolicyService`. The wrappers leave the evaluator and management implementation focused on authorization and persistence. Each evaluation (including enforcement) emits one decision event. Each management call emits a separate completion or failure event, so an owner authorization allow is not mistaken for a committed change. Events include a UTC timestamp, caller identity, action, resource, outcome, stable reason, policy ID/revision evidence, and attachment target/expected revision where applicable. Update success records the resulting revision; attach/delete record the revision checked by storage. Detach does not claim a document revision because it neither checks nor returns one. List results describe the revisions observed, not a transactional snapshot. Storage exceptions are error events rather than denials; exception messages and policy documents are excluded.

Audit delivery currently uses JSON payloads on the `jc121f1.audit.authorization` application logger. This is best-effort operational auditing, not a durable transactional audit ledger: collection/retention belongs to deployment, and a crash or sink failure can lose an event. A sink failure emits a fixed error message without changing authorization decisions, masking storage errors, or reporting an already committed mutation as failed. Guaranteed durable delivery would require a storage-backed outbox and is not implemented here.

Completion requires tests for default deny, allow/deny precedence, exact/wildcard matching, cross-account isolation, owner recovery and transfer, credential permission intersection, creator deletion, policy replacement/detachment, concurrent management changes, and storage failures. Management operations are tested through their API and denied mutations leave storage unchanged. Instance integration followed this contract and its verified tests.

## Management HTTP API

`Main` starts `AuthzWebService` on port 7072 alongside Auth (7071) and Instance (7070). It has its own Dagger component and reuses the existing authentication handler. All matched routes require authentication, including documentation; there is no public policy-management route. Send `Authorization: Bearer <session-token>` and `Content-Type: application/json`. This is a separate HTTP boundary in the existing process, not completed microservice extraction: identity reads and token authentication still use local Auth stores.

| Method | Path | JSON request | Success |
| --- | --- | --- | --- |
| POST | `/policies/create` | `document` | 201, policy with server-generated ID and revision 1 |
| POST | `/policies/describe` | `policyId` | 200, policy |
| GET | `/policies` | No body | 200, policy array |
| POST | `/policies/update` | `policyId`, `expectedRevision`, `document` | 200, replacement policy with new revision |
| POST | `/policies/delete` | `policyId`, `expectedRevision` | 204 |
| POST | `/policies/attach` | `policyId`, `expectedRevision`, `principal` | 204 |
| POST | `/policies/detach` | `policyId`, `principal` | 204 |
| POST | `/policies/attachments/list` | `principal` | 200, policy array |

`principal` is an attachment target such as `{"accountId":"a-123","subjectId":"u-456","subjectType":"USER"}`; `CREDENTIAL` is the other supported type. Caller identity always comes from authentication. Requests cannot supply policy ownership or the acting principal. Unknown fields, duplicate keys, trailing JSON, numeric enums, and fractional revisions are rejected. Body parsing is bounded to 64 KiB of bytes, including chunked requests; document limits remain stricter after parsing.

Errors distinguish invalid authentication (401), permission denial (403), invalid JSON/document/input (400), missing policy/target (404), revision or attachment conflicts (409), oversized bodies (413), and storage/internal failures (500). Denials do not disclose internal decision reasons, and parser/storage exception text is not returned. Required request fields are checked at the HTTP boundary; service validation remains authoritative for policy semantics. HTTP integration tests run real authorization, management, and audit layers against mocked persistence; the separate DynamoDB Local suite verifies storage transactions and concurrency.

## Instance integration decisions

Instances carry a persisted `accountId` assigned from the authenticated caller when created. The field is excluded from client JSON and must never be read from a request when determining ownership. A submitted `accountId` on create is ignored. State transitions retain persisted ownership. Existing rows without an account ID need explicit migration before they can be authorized; they must not inherit an account from a caller-supplied value or a default.

Public create, list, describe, start, stop, and delete calls enforce their corresponding `InstanceAction` inside `InstanceService`. Create's initial backend start is part of the create operation. Startup reconciliation and health events are internal maintenance work on stored instances, without an end-user request. The instance service calls the DI-provided audited `AuthorizationService` interface; the evaluator still depends only on action descriptors and resource references.

The list operation requires `instance:List` on the caller's account resource. Its result includes only persisted instances owned by that account for which the caller also has `instance:Describe` on the concrete instance resource. A denied describe decision excludes that row; a storage failure aborts the list instead of returning a partial success. Thus an account-scoped list grant alone does not reveal instances covered by a per-instance deny or lacking a describe grant.

Integration checkpoint 1 added the persisted, client-hidden ownership field and tests its storage mapping and JSON behavior. The user confirmed its targeted test and Checkstyle/SpotBugs checks passed. Integration checkpoint 2 passes the authenticated session into every instance service call, enforces all six actions inside the service, and applies the list rule above. HTTP handlers use `AuthContext` after authentication; missing/invalid authentication remains 401 and authorization denial maps to 403. The user confirmed its tests and checks passed after the HTTP test fixture correction. Integration checkpoint 3 exercises the real audited evaluator through the instance service and HTTP boundary, including current policy changes, credential intersection, owner behavior, account isolation, list filtering, and storage errors. An initial run exposed a test fixture restubbing error; it was corrected and the HTTP coverage expanded. The user confirmed the final tests pass. Final Checkstyle and SpotBugs results have not been reported.

## Auth identity integration checkpoint

`AuthService` now requires an authenticated caller for adding a user to an existing account, describing or deleting a user, and invalidating a credential. It resolves user and credential ownership from the identity stores and enforces the corresponding `AuthAction` through the injected audited `AuthorizationService` before returning protected data or mutating storage. The authorization evaluator supplies same-account isolation, explicit-deny precedence, credential restrictions, and owner-deletion protection. Auth HTTP middleware establishes `AuthContext`; handlers pass that trusted session to the service. Authentication failure remains 401 and permission denial maps to 403.

`POST /users/create` with no `accountId` is new-account signup and needs no existing session. The same path with `accountId` adds a user to an existing account and requires a bearer session with `auth:CreateUser` on that account. Direct service callers must use the caller overload for existing-account creation; the signup method rejects a supplied `accountId`. Login and credential exchange remain authentication operations. Credential generation remains password authenticated without a bearer session: after password verification, the service derives the user principal from the stored matching user and enforces `auth:GenerateCredential` on that account before creating the credential. Submitted identifiers never establish the acting principal.

The user confirmed the auth identity integration tests pass. Checkstyle and SpotBugs results for this checkpoint have not been separately confirmed. Test consolidation may reduce fixture duplication in a later checkpoint without removing behavior coverage.

A follow-up checkpoint adds service tests using the real audited evaluator in the existing auth authorization test class. They exercise owner read and deletion protection, explicit deny, credential and creator grant intersection, and password-authenticated credential generation before and after a grant. The user confirmed these tests pass. Checkstyle and SpotBugs results have not been separately confirmed.

## Future service decomposition

Services own their action catalogs and resource ownership data. Auth owns identities; Authz owns policies and decisions. Shared contracts can move into a small independently versioned API artifact, with service action enums in each service's contract artifact. Authz's evaluator and validator must not import compute implementations or service-owned instance enums.

Today `AuthorizationCatalogModule` assembles the deployment catalog using local enums. The registry itself consumes plain descriptors such as `{"service":"instance","operation":"Start","resourceTypes":["instance"]}`. A future deployment loader can consume approved versioned manifests instead, including services absent from this deployment's Java `ServiceId` enum. The descriptor is a data contract, not a remote registration endpoint; transport, manifest versions, and catalog distribution are not implemented yet.

Catalog publication must be controlled by deployment/service identity and restricted to the publishing service's namespace. End users and authorization requests cannot register actions or supply resource-type definitions. Unknown actions deny access. Identifiers are stable and case-sensitive: never serialize Java class names or enum ordinals, and do not rename existing wire identifiers. Adding an action can expand existing `service:*` grants; catalog changes require review with that consequence in mind. Publish a compatible catalog before rolling out callers; a stale catalog denies unknown actions.

After decomposition, authenticate both the calling service and the end-user identity. A serialized `AuthenticatedSession` supplied by a client is not proof of identity. The resource-owning service resolves ownership, requests a decision, and enforces it before side effects; Authz validates the caller's authority to assert that namespace and rechecks current principal/policy state through trusted interfaces. Do not share database access across services or silently allow requests when Authz is unavailable. Remote revocation consistency and concurrent ownership changes need explicit protocols before service extraction; the current in-process contract alone does not solve them.

## Implementation checkpoints

1. Contracts, service-owned action catalogs, policy validator, management errors, and credential creator metadata: user confirmed the checks passed, including the catalog refactor. Creator/account reassignment is rejected by normal credential-store updates, including attempts to assign an inferred creator to legacy credentials. Legacy credentials remain readable; the evaluator denies credentials without a creator.
2. Policy persistence using the extended common store: user confirmed the refactor's tests passed. Local tests require DynamoDB at localhost:8000 and `DynamoDbLocalAvailable=True`; Authz tests create and remove a uniquely named test table.
3. Authorization evaluator and enforcement: user confirmed tests passed, including the refactor separating user and credential evaluation. Strong identity reads reuse the common store. Instance integration is described above.
4. Policy-management implementation and concurrency tests: user confirmed checks passed. Unit tests cover owner enforcement on all eight operations, target validation, cleanup, immutable results, and error propagation. DynamoDB Local tests exercise the management lifecycle, evaluator visibility, and concurrent revision updates through the service.
5. Management API, audit records, and integration tests: user confirmed both the audit and management API checkpoints passed, including all eight endpoints, owner enforcement, authentication-before-parsing, account isolation, request limits, status mapping, and audit outcomes. Verification includes the user's local SpotBugs suppression adjustments. Instance integration is described above.

Each checkpoint is committed before pausing for the user to run tests. Do not proceed past a checkpoint until its results are reviewed.
