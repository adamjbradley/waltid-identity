package id.walt.authop.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One step update pushed from n8n (or any workflow engine) to the auth-op
 * page via the flow-update callback mechanism. A session accumulates a
 * sequence of these over the life of a workflow execution; the browser
 * receives them as SSE events in insertion order.
 *
 * @property sessionId the server-minted flow session id returned by
 *   `POST /api/flow-kickoff`. The callback must reference an existing
 *   session; if the session has expired the callback 404s.
 * @property step short label identifying which step emitted this update
 *   (e.g. `"dark-web"`, `"preferences"`, `"first-party"`, `"aggregate"`).
 * @property status free-form but conventionally `"completed"` or `"failed"`.
 * @property result the step's output, opaque JSON — typically the HTTP
 *   response body of the faux API call. Null for failure updates.
 * @property error human-readable error message when [status] is `"failed"`.
 * @property at event timestamp from the workflow's clock (not auth-op's),
 *   so UIs can show the original ordering even if callbacks arrive slightly
 *   out of order.
 */
@Serializable
data class FlowStepUpdate(
    val sessionId: String,
    val step: String,
    val status: String,
    val result: JsonElement? = null,
    val error: String? = null,
    val at: Instant,
)
