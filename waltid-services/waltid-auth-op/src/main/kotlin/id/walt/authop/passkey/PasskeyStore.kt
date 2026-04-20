@file:OptIn(ExperimentalTime::class)

package id.walt.authop.passkey

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.ExperimentalTime

/**
 * File-backed registry of [PasskeyCredential]s. The whole registry is held
 * in a single JSON file so that the demo deployment survives `auth-op`
 * restarts without needing a database.
 *
 * Writes are atomic: we serialize to a sibling `.tmp` file and rename it
 * over the registry. In-memory access is guarded by a [ReentrantReadWriteLock];
 * all mutations fsync via `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`.
 *
 * The on-disk JSON uses the shape `{"credentials": [...]}` so the top-level
 * can later grow (e.g. a schema version field) without a migration.
 */
class PasskeyStore(private val path: Path) {

    @Serializable
    private data class RegistryFile(val credentials: List<PasskeyCredential> = emptyList())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = ReentrantReadWriteLock()
    private var cache: List<PasskeyCredential> = load()

    private fun load(): List<PasskeyCredential> {
        if (!Files.exists(path)) return emptyList()
        return runCatching {
            json.decodeFromString<RegistryFile>(Files.readString(path)).credentials
        }.getOrElse { emptyList() }
    }

    private fun persist(next: List<PasskeyCredential>) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(RegistryFile.serializer(), RegistryFile(next)))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cache = next
    }

    /** Add a new credential (or replace an existing one for the same credentialId). */
    fun save(credential: PasskeyCredential) = lock.write {
        val next = (cache.filterNot { it.credentialId == credential.credentialId }) + credential
        persist(next)
    }

    /** All credentials registered for [sub]. Empty list if the user has none. */
    fun listBySub(sub: String): List<PasskeyCredential> = lock.read {
        cache.filter { it.sub == sub }
    }

    /** Look up a specific credential by its WebAuthn credentialId. */
    fun findByCredentialId(credentialId: String): PasskeyCredential? = lock.read {
        cache.firstOrNull { it.credentialId == credentialId }
    }

    /** Bump the signature counter after a successful assertion (replay protection). */
    fun bumpSignatureCount(credentialId: String, newCount: Long) = lock.write {
        val existing = cache.firstOrNull { it.credentialId == credentialId } ?: return@write
        val next = cache.map { if (it.credentialId == credentialId) it.copy(signatureCount = newCount) else it }
        persist(next)
    }

    /** All credentials — used when authenticating via discoverable credentials
     *  (conditional UI), where the browser supplies the credentialId without
     *  us knowing the sub first. */
    fun all(): List<PasskeyCredential> = lock.read { cache.toList() }
}
