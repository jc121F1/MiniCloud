# Lifecycle reliability design

## Scope and current foundation

Instance metadata already uses the common `InstanceStore` revision condition for state transitions. Each operation reserves a transitional state before invoking Docker, and a completion may replace only the exact revision it observed. The design keeps that store as the sole durable coordination mechanism; it adds no parallel operation table, local state store, or second persistence implementation. A reservation is the durable record of intent. In particular, a timed out start/stop is not evidence that Docker did nothing.

## Docker event stream failure and reconnection

Treat each event subscription as a replaceable connection generation. On error or unexpected completion, fail that generation's outstanding event waits promptly, then reconnect with bounded exponential backoff while the backend remains open. Do not let a callback from an obsolete generation publish events or terminate the current generation. Successful reconnection restores event-driven command completion. Start/stop must also reconcile the actual container state after a stream interruption or event deadline so a missed event cannot strand a reservation. If Docker cannot be queried, retain the transitional intent for startup recovery; do not guess `MISSING` or remove a container.

## Uncertain commands and late completion

Docker SDK transport timeout, event deadline, or caller cancellation does not prove a command stopped. Keep the instance in `STARTING` or `STOPPING` until a fresh status observation resolves the outcome. If observation is unavailable, leave the reservation intact. Any completion or health update uses the operation's original revision; a conditional-write conflict is stale work and must never trigger an unconditional fallback. Late event notifications are hints to query current Docker state, not authority to write an instance state directly. Backend container status changes are accepted only for the currently mapped container identity.

Retries and restart recovery inspect the labeled customer container and compare its actual state with the reserved intent. Complete the transition when the desired state is already present; otherwise issue an idempotent start/stop and observe again. Never recreate a container merely because an event was missed or a command outcome is ambiguous. Create recovery first searches for the existing labeled container. Explicit deletion remains the only lifecycle operation that removes a customer container.

## Startup and shutdown deadlines

Give startup reconciliation a configured overall deadline. Before the deadline, it may issue bounded operations and verify their results. At expiry, stop scheduling additional recovery work and fail startup while retaining unresolved `STARTING`, `STOPPING`, or `DELETING` metadata. A deadline cannot forcibly stop an SDK request already running; late callbacks remain revision-conditional. Partial startup cleanup closes subscriptions, clients, and executors without stopping or removing workloads.

Shutdown rejects new API work, unsubscribes event consumers, and waits for in-flight lifecycle work only up to a configured drain deadline. At expiry, close backend connections and finish resource cleanup; preserve unresolved transition reservations for the next startup to inspect. Completion handlers observing service closure do not guess a final state. Backend close must never stop or remove customer containers. Resource cleanup should continue after individual close failures.

## Coordination guarantee

Revision-conditional writes prevent stale metadata completions from overwriting newer state, including across processes that share the same `InstanceStore`. The design's backend sequencing and recovery guarantee assumes one active service process owns a Docker backend at a time, and that the previous process has stopped issuing Docker requests before the next process reconciles. Docker has no fencing token in these commands. If overlapping service processes or late requests from an old process must be safe, distributed ownership/leases plus backend-enforced fencing (or an equivalent serialized command service) are required; metadata revisions alone cannot fence Docker side effects.

## Implementation checkpoints

1. Reconnect event stream generations and cover failure, stale callbacks, and reconnection.
2. Resolve event/command uncertainty by observing actual container state; cover late events, late commands, and restart recovery without recreating customer containers.
3. Bound startup reconciliation and preserve intent on partial startup failure.
4. Bound shutdown draining and verify in-flight operation handling and container preservation.

Each checkpoint updates the lifecycle behavior documentation and adds focused tests. Tests are user-run: do not invoke Gradle or test tasks during implementation.
