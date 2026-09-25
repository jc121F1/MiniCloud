# Instance lifecycle reliability

Status: first implementation checkpoint; awaiting user-run tests and quality checks.

Instance persistence uses an internal revision to reject stale updates and deletes. Rows without a revision remain readable and acquire revision 1 on their first successful update; newly created rows also start without a revision in this checkpoint. The revision is excluded from client JSON. State changes reuse the common store's conditional transaction support, including unique-name bookkeeping; supplied persistence conditions must not bypass revision or ownership checks. Older writers do not enforce these guards, so rollout must avoid concurrent writes from old and new versions.

Create/start/stop completion is observed separately from writing the resulting state. A failed completion write is logged rather than followed by another write that could overwrite newer state. Backend failures attempt to record `MISSING` only against the operation's original revision. Health-event and reconciliation failures are observed. Delete waits for backend deletion and conditional metadata deletion before returning success; failures propagate to the caller.

This checkpoint does not serialize backend deletion against concurrent start/stop operations. A backend deletion can succeed before a conflicting metadata update is detected. The next checkpoint must define a deletion reservation/state, retry behavior, recovery after partial failure, and concurrent-operation semantics. Bounded Docker event waits are also still needed. Revision checks protect persisted state; they do not cancel in-flight backend work.

Verification includes isolated DynamoDB Local tests for concurrent writes, legacy revisions, stale completions, and deletion, plus service failure/ordering tests and model serialization checks. Run the full suite with DynamoDB Local on port 8000:

```powershell
$env:DynamoDbLocalAvailable = "True"
.\gradlew.bat test checkstyleMain checkstyleTest spotbugsMain
```

Do not proceed beyond this checkpoint until the user reports results.
