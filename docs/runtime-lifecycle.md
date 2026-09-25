# Runtime lifecycle ownership

Status: safe Docker teardown checkpoint; awaiting user-run tests and quality checks.

Closing the Docker backend releases its event subscription, event listener, and Docker client. It must not stop or remove customer containers. Explicit instance deletion remains the operation that removes a workload. Closing is repeat-safe, later calls are rejected, and client cleanup is attempted even when listener cleanup fails.

The event listener rejects new waiters after close or terminal stream failure and completes outstanding waiters. Failed/cancelled waits release their registrations. Backend command failures must also release their event waiters so retries are not blocked by abandoned registrations. Automatic event-stream reconnection and event-wait deadlines are not implemented in this checkpoint.

Full application resource ownership remains follow-up work: replace constructor side effects with explicit startup, give executors and clients clear scopes, stop accepting requests before teardown, bound shutdown waiting, and release all owned resources on partial startup failure. The backend does not own the injected executor and must not shut it down itself. These changes must preserve workloads and coordinate with instance reconciliation.

Run the Docker backend/listener unit tests as part of the full suite:

```powershell
.\gradlew.bat test checkstyleMain checkstyleTest spotbugsMain
```

Do not proceed beyond this checkpoint until the user reports results.
