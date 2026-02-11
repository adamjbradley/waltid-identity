package id.walt.issuer.tenant

import id.walt.commons.config.ConfigManager
import id.walt.crypto.keys.KeyManager
import id.walt.crypto.keys.Key
import id.walt.issuer.config.CredentialTypeConfig
import id.walt.issuer.config.OIDCIssuerServiceConfig
import id.walt.issuer.issuance.CIProvider
import id.walt.oid4vc.OpenID4VCIVersion
import id.walt.oid4vc.data.CredentialSupported
import id.walt.oid4vc.providers.CredentialIssuerConfig
import io.klogging.noCoLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

object IssuerTenantRegistry {

    private val log = noCoLogger("IssuerTenantRegistry")
    private val providers = ConcurrentHashMap<String, CIProvider>()
    private val tenantTokenKeys = ConcurrentHashMap<String, Key>()

    fun getOrCreate(tenant: IssuerTenant): CIProvider {
        return providers.getOrPut(tenant.id) {
            createProviderForTenant(tenant)
        }
    }

    fun getTokenKey(tenant: IssuerTenant): Key {
        return tenantTokenKeys.getOrPut(tenant.id) {
            resolveTokenKey(tenant)
        }
    }

    fun invalidate(tenantId: String) {
        providers.remove(tenantId)
        tenantTokenKeys.remove(tenantId)
        log.info("Invalidated cached CIProvider and token key for tenant: $tenantId")
    }

    fun providerCount(): Int = providers.size

    private fun createProviderForTenant(tenant: IssuerTenant): CIProvider {
        val globalBaseUrl = ConfigManager.getConfig<OIDCIssuerServiceConfig>().baseUrl
        val tenantBaseUrl = "$globalBaseUrl/issuers/${tenant.id}"

        val credentialConfig = parseCredentialConfigurations(tenant.credentialConfigurations)

        log.info("Creating CIProvider for tenant ${tenant.legalName} (${tenant.id}) with ${credentialConfig.size} credential types")

        return CIProvider(
            baseUrl = "$tenantBaseUrl/${OpenID4VCIVersion.DRAFT13.versionString}",
            baseUrlDraft11 = "$tenantBaseUrl/${OpenID4VCIVersion.DRAFT11.versionString}",
            config = CredentialIssuerConfig(
                credentialConfigurationsSupported = credentialConfig
            )
        )
    }

    private fun resolveTokenKey(tenant: IssuerTenant): Key {
        val ciTokenKeyStr = tenant.ciTokenKey
            ?: throw IllegalStateException("Tenant ${tenant.id} has no ciTokenKey configured")
        return runBlocking {
            KeyManager.resolveSerializedKey(
                Json.parseToJsonElement(ciTokenKeyStr).jsonObject
            )
        }
    }

    private fun parseCredentialConfigurations(
        configs: Map<String, JsonElement>
    ): Map<String, CredentialSupported> {
        if (configs.isEmpty()) return emptyMap()

        // Reuse CredentialTypeConfig's parse logic
        val typeConfig = CredentialTypeConfig(supportedCredentialTypes = configs)
        return typeConfig.parse()
    }
}
