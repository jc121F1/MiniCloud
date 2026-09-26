# Instance lifecycle reliability

Status: the user confirmed the revision guards, follow-up fix, and deletion-reservation checkpoint below passed tests and quality checks.

Instance persistence uses an internal revision to reject stale updates and deletes. Rows without a revision remain readable and acquire revision 1 on their first successful update; newly created rows also start without a revision in this checkpoint. The revision is excluded from client JSON. State changes reuse the common store's conditional transaction support, including unique-name bookkeeping; supplied persistence conditions must not bypass revision or ownership checks. Older writers do not enforce these guards, so rollout must avoid concurrent writes from old and new versions.

Create/start/stop completion is observed separately from writing the resulting state. A failed completion write is logged rather than followed by another write that could overwrite newer state. Backend failures attempt to record `MISSING` only against the operation's original revision. Health-event and reconciliation failures are observed. Delete waits for backend deletion and conditional metadata deletion before returning success; failures propagate to the caller.

## Deletion reservation checkpoint

Delete authorizes the caller before reserving the current revision as `DELETING`. Only `RUNNING`, `STOPPED`, and `MISSING` may enter deletion; deleting an instance in `STARTING` or `STOPPING` returns a conflict without invoking the backend. The reservation competes with start/stop through the existing revision condition. If deletion wins, subsequent start/stop calls conflict; if start/stop wins, deletion must wait for that operation to finish and be retried. Stale readers lose the conditional write before making backend calls.

The service waits for backend deletion and then conditionally deletes the reserved row and its unique-name reservation. Success retains the existing HTTP 200 response shape. Backend or metadata failures propagate and leave `DELETING` intact: restoring the previous state would be unsafe because backend deletion may already have happened. Authorized retries of `DELETING` repeat idempotent backend deletion and retry metadata removal. A missing backend instance is a successful backend deletion. Failed attempts retain the instance name until metadata deletion succeeds. The object remains visible to authorized describe/list callers as `DELETING` while recovery is pending.

Concurrent retries may both invoke idempotent backend deletion. Only one conditional metadata deletion wins; a loser reports a conflict. After the row is gone, a fresh delete returns not found under the existing API contract. Retries are therefore safe for backend effects but do not promise identical HTTP responses.

Startup reconciliation resumes `DELETING` without recreating or starting it. A failure preserves the deletion intent for another authorized retry or restart. Recovery of a stable `RUNNING`/`STOPPED` row that requires backend changes first reserves `STARTING`/`STOPPING`, protecting it from concurrent deletion. Health events only mark stable `RUNNING` instances missing or restore `MISSING` to running; they cannot release a transitional or deletion reservation. Backend completion and persistence completion are observed separately, including during reconciliation, so a stale completion write does not trigger a second attempt to mark the instance missing.

This is not distributed backend fencing. Startup recovery assumes the previous service process has stopped; overlapping active service processes can still recover the same transitional row. Backend timeouts and transport failures may have uncertain effects, and failed futures do not cancel backend work. Revision conditions protect metadata and serialize competing API reservations, but do not establish that a timed-out backend command has stopped. Distributed ownership/leases and reconciliation of ambiguous backend outcomes remain follow-up work. Deploy the new enum and guards together; old writers cannot safely participate.

Verification includes isolated DynamoDB Local tests for concurrent writes, legacy revisions, stale completions, and competing start/deletion reservations. Service tests cover reservation-before-backend ordering, transitional deletion rejection, health events during pending operations, failed reservation with no backend effect, partial failures/retries, startup deletion recovery, and reserved startup repair. HTTP tests verify conflicts for deleting transitional instances and starting/stopping deleting instances. Run the full suite with DynamoDB Local on port 8000:

```powershell
$env:DynamoDbLocalAvailable = "True"
.\gradlew.bat test checkstyleMain checkstyleTest spotbugsMain
```

Do not proceed beyond this checkpoint until the user reports results.
