package id.walt.authop.config

import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.decoder.Decoder
import com.sksamuel.hoplite.fp.Validated
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Hoplite decoder that matches HOCON strings to enum constants case-insensitively
 * and only for the auth-op config enums ([RealmMethod], [SubStrategy], [TokenEndpointAuthMethod]).
 *
 * HOCON convention uses lowercase snake_case (`"oidc"`, `"claim_hash"`, `"client_secret_basic"`)
 * but Kotlin enum constants are UPPER_SNAKE_CASE. This decoder bridges the two for our types
 * without globally overriding Hoplite's strict enum matching elsewhere.
 *
 * Only decodes the three enum classes it recognises — falls through via [supports] for any
 * other enum the main config happens to include.
 */
internal class CaseInsensitiveEnumDecoder : Decoder<Any> {

    private val supported: Set<KClass<out Enum<*>>> = setOf(
        RealmMethod::class,
        SubStrategy::class,
        TokenEndpointAuthMethod::class,
    )

    override fun supports(type: KType): Boolean {
        val classifier = type.classifier as? KClass<*> ?: return false
        return classifier in supported
    }

    override fun decode(node: Node, type: KType, context: DecoderContext): ConfigResult<Any> {
        if (node !is StringNode) {
            return Validated.Invalid(ConfigFailure.DecodeError(node, type))
        }
        val enumClass = (type.classifier as KClass<*>)
        val wanted = node.value.uppercase()
        val constant = enumClass.java.enumConstants
            ?.firstOrNull { (it as Enum<*>).name == wanted }
        return if (constant != null) {
            Validated.Valid(constant)
        } else {
            Validated.Invalid(ConfigFailure.DecodeError(node, type))
        }
    }
}
