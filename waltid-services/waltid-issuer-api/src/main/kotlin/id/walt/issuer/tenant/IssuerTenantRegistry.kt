package id.walt.issuer.tenant

import id.walt.commons.config.ConfigManager
import id.walt.crypto.keys.KeyManager
import id.walt.crypto.keys.Key
import id.walt.issuer.config.CredentialTypeConfig
import id.walt.issuer.config.OIDCIssuerServiceConfig
import id.walt.issuer.issuance.CIProvider
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.oid4vc.data.AuthenticationMethod
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
        val config = ConfigManager.getConfig<OIDCIssuerServiceConfig>()
        val globalBaseUrl = config.externalBaseUrl ?: config.baseUrl
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

    /**
     * Build default IssuanceRequests for the "Add document from list" flow where the wallet
     * sends authorization_details with credential_configuration_ids but no credential offer exists.
     */
    fun buildDefaultIssuanceRequests(
        tenant: IssuerTenant,
        credentialConfigurationIds: List<String>
    ): List<IssuanceRequest> {
        val provider = getOrCreate(tenant)
        val supported = provider.config.credentialConfigurationsSupported

        // Wrap raw JWK in {"type":"jwk","jwk":{...}} format expected by KeyManager
        val wrappedKey = tenant.issuerKey?.let { rawJwk ->
            if (rawJwk.containsKey("type")) rawJwk
            else buildJsonObject {
                put("type", JsonPrimitive("jwk"))
                put("jwk", rawJwk)
            }
        }

        // Wrap x5Chain base64 DER in PEM format for X509CertUtils.parse()
        val wrappedX5Chain = tenant.x5Chain?.map { certBase64 ->
            "-----BEGIN CERTIFICATE-----\n${certBase64}\n-----END CERTIFICATE-----"
        }

        return credentialConfigurationIds.mapNotNull { configId ->
            val credSupported = supported[configId] ?: return@mapNotNull null
            val format = credSupported.format
            val isMdoc = format == CredentialFormat.mso_mdoc
            val isSdJwt = format == CredentialFormat.sd_jwt_vc || format == CredentialFormat.sd_jwt_dc

            val docType = credSupported.docType ?: configId

            when {
                isMdoc -> IssuanceRequest(
                    issuerKey = wrappedKey,
                    credentialConfigurationId = configId,
                    credentialFormat = format,
                    mdocData = buildDefaultMdocData(docType, tenant),
                    x5Chain = wrappedX5Chain,
                    authenticationMethod = AuthenticationMethod.PWD,
                )
                isSdJwt -> IssuanceRequest(
                    issuerKey = wrappedKey,
                    credentialConfigurationId = configId,
                    credentialFormat = format,
                    credentialData = buildDefaultSdJwtData(credSupported.vct ?: configId, tenant),
                    vct = credSupported.vct,
                    x5Chain = wrappedX5Chain,
                    authenticationMethod = AuthenticationMethod.PWD,
                )
                else -> null
            }
        }
    }

    /**
     * Replace default credential data with actual user claims from Keycloak ID token.
     */
    fun enrichIssuanceRequestsWithUserClaims(
        requests: List<IssuanceRequest>,
        userClaims: JsonObject,
        tenant: IssuerTenant
    ): List<IssuanceRequest> {
        fun claim(name: String): String? = userClaims[name]?.jsonPrimitive?.contentOrNull

        val givenName = claim("given_name") ?: claim("preferred_username") ?: "User"
        val familyName = claim("family_name") ?: ""
        val birthdate = claim("birthdate") ?: "1990-01-01"
        val email = claim("email")
        val nationality = claim("nationality")
        val gender = claim("gender")
        val residentAddress = claim("resident_address")
        val residentCity = claim("resident_city")
        val residentState = claim("resident_state")
        val residentPostalCode = claim("resident_postal_code")
        val residentCountry = claim("resident_country")
        val birthPlace = claim("birth_place")

        return requests.map { request ->
            val format = request.credentialFormat
            val isMdoc = format == CredentialFormat.mso_mdoc
            val isSdJwt = format == CredentialFormat.sd_jwt_vc || format == CredentialFormat.sd_jwt_dc

            when {
                isMdoc && request.mdocData != null -> {
                    val updatedMdocData = request.mdocData.mapValues { (_, fields) ->
                        buildJsonObject {
                            // Keep base fields (issuing_country, issuing_authority, dates)
                            fields.forEach { (key, value) -> put(key, value) }
                            // Override with user's identity
                            put("given_name", givenName)
                            put("family_name", familyName)
                            put("birth_date", birthdate)
                            if (nationality != null) put("nationality", nationality)
                            if (gender != null) put("gender", gender)
                            if (residentAddress != null) put("resident_address", residentAddress)
                            if (residentCity != null) put("resident_city", residentCity)
                            if (residentState != null) put("resident_state", residentState)
                            if (residentPostalCode != null) put("resident_postal_code", residentPostalCode)
                            if (residentCountry != null) put("resident_country", residentCountry)
                            if (birthPlace != null) put("birth_place", birthPlace)
                        }
                    }
                    request.copy(mdocData = updatedMdocData)
                }
                isSdJwt && request.credentialData != null -> {
                    val updatedData = buildJsonObject {
                        request.credentialData.forEach { (key, value) -> put(key, value) }
                        put("given_name", givenName)
                        put("family_name", familyName)
                        put("birthdate", birthdate)
                        if (email != null) put("email", email)
                        if (nationality != null) put("nationality", nationality)
                        if (gender != null) put("gender", gender)
                        if (residentAddress != null) put("resident_address", residentAddress)
                        if (residentCity != null) put("resident_city", residentCity)
                        if (residentState != null) put("resident_state", residentState)
                        if (residentPostalCode != null) put("resident_postal_code", residentPostalCode)
                        if (residentCountry != null) put("resident_country", residentCountry)
                        if (birthPlace != null) put("birth_place", birthPlace)
                    }
                    request.copy(credentialData = updatedData)
                }
                else -> request
            }
        }
    }

    private fun buildDefaultMdocData(docType: String, tenant: IssuerTenant): Map<String, JsonObject> {
        val namespace = when {
            docType.contains("pid") -> "eu.europa.ec.eudi.pid.1"
            docType.contains("mDL") || docType.contains("mdl") -> "org.iso.18013.5.1"
            else -> docType
        }
        val fields = buildJsonObject {
            put("family_name", "Demo")
            put("given_name", "User")
            put("birth_date", "1990-01-01")
            put("issue_date", "2026-01-01")
            put("expiry_date", "2027-01-01")
            put("issuing_country", tenant.country)
            put("issuing_authority", tenant.legalName)
        }
        return mapOf(namespace to fields)
    }

    private fun buildDefaultSdJwtData(vct: String, tenant: IssuerTenant): JsonObject {
        return buildJsonObject {
            put("family_name", "Demo")
            put("given_name", "User")
            // Emit both spellings: EUDI/ISO (birth_date) used by DCQL queries
            // for EUDI PID, and OIDC (birthdate) expected by SD-JWT VC / OIDC
            // consumers. Without birth_date the wallet can't satisfy DCQL
            // `claim [birth_date]` selectors and presentation fails.
            put("birth_date", "1990-01-01")
            put("birthdate", "1990-01-01")
            put("issuing_country", tenant.country)
            put("issuing_authority", tenant.legalName)
            put("age_over_18", true)
            put("age_over_21", true)
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
