package id.walt.authop.claims

import com.nfeld.jsonpathkt.JsonPath
import com.nfeld.jsonpathkt.kotlinx.resolveOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pure, stateless mapper: given a JSON [payload] (upstream OIDC ID-token claims
 * OR a presented credential body, depending on the realm type) and a mapping
 * `output_name -> JSONPath`, returns the flat map of mapped claims.
 *
 * **Design constraints:**
 *  - Pure function of `(payload, mapping)` → result. No side effects, no
 *    configuration, no default values. Reusable by both Task 14 (OIDC payload)
 *    and Task 19 (VP payload) — same API shape, different payload content.
 *  - Missing paths are silently omitted — never surface as `null` entries and
 *    never throw. The realm operator chose what to project; an absent claim is
 *    a runtime fact, not an error.
 *  - No transformations, no type coercion. Whatever JSONPath returns is what
 *    the mapper returns, preserving the upstream's JSON type exactly. Callers
 *    that need strings use `.jsonPrimitive.content` at the call site; giving
 *    them the raw `JsonElement` keeps options open (e.g. a mapped claim may
 *    legitimately be an array or object when the realm projects grouped data).
 *
 * **JSONPath subset supported:** whatever `com.eygraber:jsonpathkt-kotlinx`
 * supports. That includes root (`$`), dot notation (`$.a.b`), bracket notation
 * (`$["a"]`), and array index (`$.a[0]`). Wildcards, filters, and union syntax
 * also work; we do not restrict them but realms should prefer the simple
 * field-access forms for readability.
 *
 * **Duplicate output names:** the [mapping] is a [Map], so Kotlin guarantees
 * a single entry per key — there is no "later wins" ambiguity because the
 * input itself cannot carry duplicates. If a realm config surface ever
 * switches to ordered pairs, revisit.
 */
object ClaimMapper {
    fun apply(payload: JsonObject, mapping: Map<String, String>): Map<String, JsonElement> {
        if (mapping.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, JsonElement>(mapping.size)
        for ((outputName, path) in mapping) {
            // JsonPath.compile throws on syntactically invalid paths. We let
            // that propagate — it's a realm-config bug, not a runtime data
            // event, and loud failure at first touch beats silently losing a
            // claim. Production realms should be validated at config load
            // (future hardening); until then, a bad path crashes the flow,
            // which is the right kind of loud.
            val compiled = JsonPath.compile(path)
            val value: JsonElement? = payload.resolveOrNull(compiled)
            if (value != null) out[outputName] = value
        }
        return out
    }
}
