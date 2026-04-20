package id.walt.authop.tokens

import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Load-or-generate the auth-op signing key.
 *
 * On first call with a missing file, generates an RSA-2048 [JWKKey] and persists it
 * (including the private part, as returned by [JWKKey.exportJWK]) to [path]. Subsequent
 * calls read the same file and rehydrate the key — so RP JWKS caches keyed on `kid`
 * do not invalidate on every service restart.
 *
 * Scope boundaries (v1):
 * - Single active key. No rotation, no multi-key JWKS.
 * - File IO only. Production mounts an externally-managed key file at the same path.
 * - RSA-2048 hard-coded (add a `keyType` parameter when EC is actually needed).
 * - Caller is responsible for the parent directory's existence. We do NOT mkdirs —
 *   that keeps the API honest about what it owns.
 *
 * Corrupt / empty file behaviour: we fail loudly with [KeyProviderException] rather
 * than silently regenerating. Silent regeneration would be a security footgun —
 * anyone who truncates the file would rotate keys without consent.
 */
object KeyProvider {

    suspend fun loadOrCreate(path: Path): JWKKey =
        if (path.exists()) load(path) else create(path)

    private suspend fun load(path: Path): JWKKey {
        val jwk = path.readText()
        if (jwk.isBlank()) {
            throw KeyProviderException(
                path = path.toString(),
                message = "Signing key file at '$path' is empty. Refusing to regenerate — " +
                        "delete the file explicitly if a new key is intended."
            )
        }
        return JWKKey.importJWK(jwk).getOrElse { cause ->
            throw KeyProviderException(
                path = path.toString(),
                message = "Signing key file at '$path' could not be parsed as JWK. Refusing to " +
                        "regenerate — delete the file explicitly if a new key is intended.",
                cause = cause
            )
        }
    }

    private suspend fun create(path: Path): JWKKey {
        val key = JWKKey.generate(KeyType.RSA)
        path.writeText(key.exportJWK())
        return key
    }
}

/**
 * Signals a failure while loading the auth-op signing key from disk.
 *
 * Extends [IllegalStateException] because a non-loadable signing key leaves the
 * service in an unrunnable state — callers that treat startup failures as fatal
 * can catch the superclass without a specific import.
 */
class KeyProviderException(
    val path: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
