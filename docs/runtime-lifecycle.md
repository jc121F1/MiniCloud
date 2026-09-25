# Runtime lifecycle ownership

Status: safe Docker teardown checkpoint passed user-run tests and quality checks. Event-deadline and repeatable-delete checkpoint awaits verification.

Closing the Docker backend releases its event subscription, event listener, and Docker client. It must not stop or remove customer containers. Explicit instance deletion remains the operation that removes a workload. Closing is repeat-safe, later calls are rejected, and client cleanup is attempted even when listener cleanup fails.

The event listener rejects new waiters after close or terminal stream failure and completes outstanding waiters. Failed/cancelled waits release their registrations. Backend command failures also release their event waiters so retries are not blocked by abandoned registrations. Automatic event-stream reconnection remains follow-up work.

Each event wait has a 60-second deadline beginning at registration, before command submission. Java's CompletableFuture timeout support removes completed waits' timers without a separate application-owned scheduler. Timeout, cancellation, stream termination, command failure, and executor rejection release the registration. Cleanup only removes that specific future, so an older wait cannot remove a later retry.

Start/stop operations retain command-completion gating: an expired event wait does not finish the operation while its Docker command remains in flight. Queued commands check expiry/cancellation before execution and are skipped if already abandoned. Cancelling the returned future releases its event wait. Neither cancellation nor a deadline interrupts an already-running Docker command; the SDK transport timeouts still govern that request, and a timed-out request can have uncertain late effects at Docker. This checkpoint does not claim a hard end-to-end deadline or safe distributed fencing of late commands.

Docker deletion is repeatable: an absent container mapping or a Docker not-found response counts as success. Other Docker failures propagate and preserve the mapping for retry. This supports retrying an instance deletion when its container removal succeeded but its subsequent metadata deletion failed.

Full application resource ownership remains follow-up work: replace constructor side effects with explicit startup, give executors and clients clear scopes, stop accepting requests before teardown, bound shutdown waiting, and release all owned resources on partial startup failure. The backend does not own the injected executor and must not shut it down itself. These changes must preserve workloads and coordinate with instance reconciliation.

Deadline tests advance futures manually through a package-private deadline seam; they do not sleep or wait for a real 60-second timer. Run the Docker backend/listener unit tests as part of the full suite:

```powershell
.\gradlew.bat test checkstyleMain checkstyleTest spotbugsMain
```

Do not proceed beyond this checkpoint until the user reports results.
