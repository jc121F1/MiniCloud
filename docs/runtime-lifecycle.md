# Runtime lifecycle ownership

Status: safe Docker teardown and event-deadline/repeatable-delete checkpoints passed user-run tests and quality checks. Application resource ownership below awaits verification.

Closing the Docker backend releases its event subscription, event listener, and Docker client. It must not stop or remove customer containers. Explicit instance deletion remains the operation that removes a workload. Closing is repeat-safe, later calls are rejected, and client cleanup is attempted even when listener cleanup fails.

The event listener rejects new waiters after close or terminal stream failure and completes outstanding waiters. Failed/cancelled waits release their registrations. Backend command failures also release their event waiters so retries are not blocked by abandoned registrations. Automatic event-stream reconnection remains follow-up work.

Each event wait has a 60-second deadline beginning at registration, before command submission. Java's CompletableFuture timeout support removes completed waits' timers without a separate application-owned scheduler. Timeout, cancellation, stream termination, command failure, and executor rejection release the registration. Cleanup only removes that specific future, so an older wait cannot remove a later retry.

Start/stop operations retain command-completion gating: an expired event wait does not finish the operation while its Docker command remains in flight. Queued commands check expiry/cancellation before execution and are skipped if already abandoned. Cancelling the returned future releases its event wait. Neither cancellation nor a deadline interrupts an already-running Docker command; the SDK transport timeouts still govern that request, and a timed-out request can have uncertain late effects at Docker. This checkpoint does not claim a hard end-to-end deadline or safe distributed fencing of late commands.

Docker deletion is repeatable: an absent container mapping or a Docker not-found response counts as success. Other Docker failures propagate and preserve the mapping for retry. This supports retrying an instance deletion when its container removal succeeded but its subsequent metadata deletion failed.

`Main` now installs one shutdown hook for `ApplicationRuntime`. Every component registers its resource owner before constructing its web service or resolving handlers. Failed construction/startup closes the failed component's resources and all services already started. Normal shutdown stops all HTTP services before closing component resources. Web service start/close is repeat-safe and cannot restart after close; callers using `create()` directly still own the returned Javalin instance.

Each Dagger component shares one DynamoDB client and one virtual-thread executor. Providers register resources immediately. Docker client/listener remain individually owned if backend construction fails; once construction succeeds, their ownership transfers to the backend that already closes both. Component shutdown closes backend resources first, drains its executor for up to five seconds, requests interruption if needed, waits up to five more seconds, then closes data clients and remaining mDNS responders. Cleanup failures are logged and do not skip other resources. mDNS registration failure closes its partial responder; duplicate registration does not create another one.

The executor wait is bounded; this is not a hard bound on the entire shutdown path. HTTP, SDK, stream callbacks, or mDNS close calls may still block. Closing the backend before draining workers can fail queued/in-flight operations, including writing `MISSING` from completion callbacks; DynamoDB remains open for those callbacks until the worker drain finishes. Customer containers are preserved. Constructor-side I/O and reconciliation still exist and may delay startup/shutdown coordination. Explicit asynchronous startup/readiness, preserving uncertain operation intent during shutdown, and a global teardown deadline remain follow-up work.

Deadline tests advance futures manually through a package-private deadline seam; they do not sleep or wait for a real 60-second timer. Runtime tests cover HTTP-before-dependency ordering, partial-start rollback, ownership transfer, bounded executor shutdown/interruption, and repeated close. Web/mDNS tests use mocks without opening ports or multicast responders. Run the full suite:

```powershell
.\gradlew.bat test checkstyleMain checkstyleTest spotbugsMain
```

Do not proceed beyond this checkpoint until the user reports results.
