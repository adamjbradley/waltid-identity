package id.walt.authop.config

/**
 * Signals a failure while loading an auth-op HOCON config file.
 *
 * Extends [IllegalArgumentException] to stay polymorphically catchable by existing
 * handlers that already treat config errors as invalid-argument failures (matching
 * the repo-standard `ConfigurationException` in `waltid-service-commons`), while
 * giving a specific type that distinguishes config load failures from other
 * `IllegalArgumentException`s.
 *
 * The [path] of the offending config file is preserved as a field so callers can
 * render targeted operator messages without reparsing the exception message.
 */
class ConfigLoadException(
    val path: String,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)
