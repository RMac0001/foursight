package io.github.rmac0001.foursight.core.secrets

/**
 * Device-local storage for client keys, secrets and tokens (spec: Architecture, "Tokens stay on the
 * device"). Windows uses Credential Manager; Android uses a Keystore-protected key. Values never
 * leave the device: they're not synced, exported, logged, or returned by the MCP server.
 */
interface SecretStore {
    fun read(name: SecretName): String?

    fun write(
        name: SecretName,
        value: String,
    )

    fun delete(name: SecretName)
}

/**
 * Names of every secret FourSight uses. On Windows each one is a generic credential whose target
 * is [target] (for example `FourSight/tiktok/client_key`); Roger enters the client keys himself.
 */
enum class SecretName(
    val target: String,
    val enteredByRoger: Boolean,
) {
    TIKTOK_CLIENT_KEY("FourSight/tiktok/client_key", enteredByRoger = true),
    TIKTOK_CLIENT_SECRET("FourSight/tiktok/client_secret", enteredByRoger = true),
    TIKTOK_REFRESH_TOKEN("FourSight/tiktok/refresh_token", enteredByRoger = false),
    GOOGLE_CLIENT_ID("FourSight/google/client_id", enteredByRoger = true),
    GOOGLE_CLIENT_SECRET("FourSight/google/client_secret", enteredByRoger = true),
    GOOGLE_REFRESH_TOKEN("FourSight/google/refresh_token", enteredByRoger = false),
    META_APP_ID("FourSight/meta/app_id", enteredByRoger = true),
    META_APP_SECRET("FourSight/meta/app_secret", enteredByRoger = true),
    META_USER_TOKEN("FourSight/meta/user_token", enteredByRoger = false),
}

/** For tests and developer builds only. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<SecretName, String>()

    override fun read(name: SecretName) = values[name]

    override fun write(
        name: SecretName,
        value: String,
    ) {
        values[name] = value
    }

    override fun delete(name: SecretName) {
        values.remove(name)
    }
}
