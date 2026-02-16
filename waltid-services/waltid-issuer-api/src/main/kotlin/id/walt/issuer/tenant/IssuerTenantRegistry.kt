package id.walt.issuer.tenant

import id.walt.commons.config.ConfigManager
import id.walt.crypto.keys.KeyManager
import id.walt.crypto.keys.Key
import id.walt.issuer.config.CredentialTypeConfig
import id.walt.issuer.config.OIDCIssuerServiceConfig
import id.walt.issuer.issuance.CIProvider
import id.walt.oid4vc.OpenID4VCIVersion
import id.walt.oid4vc.data.*
import id.walt.oid4vc.providers.CredentialIssuerConfig
import io.klogging.noCoLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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

    fun resetForTesting() {
        providers.clear()
        tenantTokenKeys.clear()
    }

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

        // Handle legacy format: {"credentials": [{configId, format, vct, claims}]}
        val credentialsArray = configs["credentials"]
        if (credentialsArray is JsonArray && configs.size == 1) {
            return parseLegacyCredentialArray(credentialsArray)
        }

        // Standard format: {configId: CredentialSupportedJsonObject}
        val typeConfig = CredentialTypeConfig(supportedCredentialTypes = configs)
        return typeConfig.parse()
    }

    private fun parseLegacyCredentialArray(credentials: JsonArray): Map<String, CredentialSupported> {
        val result = mutableMapOf<String, CredentialSupported>()
        for (element in credentials) {
            if (element !is JsonObject) continue
            val obj = element.jsonObject
            val configId = obj["configId"]?.jsonPrimitive?.contentOrNull ?: continue
            val formatStr = obj["format"]?.jsonPrimitive?.contentOrNull ?: continue
            val credFormat = CredentialFormat.fromValue(formatStr) ?: continue
            val vct = obj["vct"]?.jsonPrimitive?.contentOrNull
            val docType = obj["docType"]?.jsonPrimitive?.contentOrNull
                ?: obj["doctype"]?.jsonPrimitive?.contentOrNull

            val isSdJwt = credFormat == CredentialFormat.sd_jwt_vc || credFormat == CredentialFormat.sd_jwt_dc
            val isMdoc = credFormat == CredentialFormat.mso_mdoc

            result[configId] = CredentialSupported(
                format = credFormat,
                cryptographicBindingMethodsSupported = when {
                    isSdJwt -> setOf("jwk")
                    isMdoc -> setOf("cose_key")
                    else -> setOf("did")
                },
                credentialSigningAlgValuesSupported = setOf(CredSignAlgValues.Named("ES256")),
                proofTypesSupported = mapOf(
                    ProofType.jwt to ProofTypeMetadata(setOf("ES256"))
                ),
                vct = if (isSdJwt) vct else null,
                docType = if (isMdoc) (docType ?: configId) else null,
            )
            log.info("Parsed legacy credential config: $configId (format=$formatStr, vct=$vct)")
        }
        return result
    }
}
