@file:OptIn(ExperimentalTime::class)

package id.walt.issuer.tenant

import id.walt.crypto.utils.UuidUtils.randomUUIDString
import id.walt.oid4vc.OpenID4VC
import id.walt.oid4vc.OpenID4VCI
import id.walt.oid4vc.data.*
import id.walt.oid4vc.data.dif.PresentationDefinition
import id.walt.oid4vc.data.dif.PresentationDefinition.Companion.generateDefaultEBSIV3InputDescriptor
import id.walt.oid4vc.data.dif.PresentationSubmission
import id.walt.oid4vc.definitions.JWTClaims
import id.walt.oid4vc.errors.*
import id.walt.oid4vc.providers.TokenTarget
import id.walt.oid4vc.requests.AuthorizationRequest
import id.walt.oid4vc.requests.BatchCredentialRequest
import id.walt.oid4vc.requests.CredentialRequest
import id.walt.oid4vc.requests.TokenRequest
import id.walt.oid4vc.responses.AuthorizationErrorCode
import id.walt.oid4vc.responses.CredentialErrorCode
import id.walt.oid4vc.responses.PushedAuthorizationResponse
import id.walt.commons.config.ConfigManager
import id.walt.issuer.config.OIDCIssuerServiceConfig
import id.walt.issuer.issuance.CIProvider
import id.walt.issuer.issuance.ClientAttestationHandler
import id.walt.issuer.issuance.DPoPHandler
import id.walt.policies.Verifier
import id.walt.policies.models.PolicyRequest.Companion.parsePolicyRequests
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.klogging.noCoLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

private val log = noCoLogger("TenantOidcRoutes")

fun Application.tenantOidcRoutes() {
    routing {
        route("issuers/{issuerId}") {

            // -- Tenant resolution helper --
            // Each route handler uses this to resolve + validate the tenant

            route("{standardVersion}") {

                get(".well-known/openid-credential-issuer") {
                    val (tenant, provider) = resolveTenantProvider(call) ?: return@get
                    val metadata = provider.getMetadataByVersion(
                        standardVersion = call.parameters["standardVersion"]
                    )
                    // Inject tenant display info (OpenID4VCI spec §11.2.3)
                    val metadataMap = metadata.toJSON().toMutableMap()
                    metadataMap["display"] = buildJsonArray {
                        addJsonObject {
                            put("name", tenant.legalName)
                            put("locale", "en")
                        }
                    }

                    // Ensure credential configs have binding methods + proof types (required for wallet key proofs)
                    // and scope (iOS EudiWalletKit filters out configs without scope — see
                    // OpenId4VciService.getCredentialOfferedModels).
                    val configs = metadataMap["credential_configurations_supported"]?.jsonObject
                    if (configs != null) {
                        metadataMap["credential_configurations_supported"] = JsonObject(
                            configs.mapValues { (configId, configElement) ->
                                val config = configElement.jsonObject
                                val patched = config.toMutableMap()
                                if (config["cryptographic_binding_methods_supported"] == null) {
                                    val format = config["format"]?.jsonPrimitive?.contentOrNull
                                    val isMdoc = format == "mso_mdoc"
                                    val isSdJwt = format == "dc+sd-jwt" || format == "vc+sd-jwt"
                                    patched["cryptographic_binding_methods_supported"] = buildJsonArray {
                                        add(JsonPrimitive(when {
                                            isMdoc -> "cose_key"
                                            isSdJwt -> "jwk"
                                            else -> "did"
                                        }))
                                    }
                                    patched["proof_types_supported"] = buildJsonObject {
                                        putJsonObject("jwt") {
                                            put("proof_signing_alg_values_supported", buildJsonArray {
                                                add(JsonPrimitive("ES256"))
                                            })
                                        }
                                    }
                                }
                                if (config["scope"] == null) {
                                    patched["scope"] = JsonPrimitive(configId)
                                }
                                if (patched.size != config.size || patched["scope"] != config["scope"]) {
                                    JsonObject(patched)
                                } else {
                                    configElement
                                }
                            }
                        )
                    }

                    // Advertise batch issuance (see OidcApi.BATCH_SIZE_ADVERTISED).
                    metadataMap["batch_credential_issuance"] = buildJsonObject {
                        put("batch_size", JsonPrimitive(1000))
                    }
                    call.respond(JsonObject(metadataMap))
                }

                get(".well-known/openid-configuration") {
                    val (_, provider) = resolveTenantProvider(call) ?: return@get
                    val metadata = provider.getOpenIdProviderMetadataByVersion(
                        standardVersion = call.parameters["standardVersion"]
                    )
                    call.respond(metadata.toJSON())
                }

                get(".well-known/oauth-authorization-server") {
                    val (_, provider) = resolveTenantProvider(call) ?: return@get
                    val metadata = provider.getOpenIdProviderMetadataByVersion(
                        standardVersion = call.parameters["standardVersion"]
                    )
                    call.respond(metadata.toJSON())
                }

                get("jwks") {
                    val (_, provider) = resolveTenantProvider(call) ?: return@get
                    call.respond(
                        status = HttpStatusCode.OK,
                        message = provider.getJwksSessions()
                    )
                }

                get("credentialOffer") {
                    val (_, provider) = resolveTenantProvider(call) ?: return@get
                    val sessionId = call.parameters["id"] ?: throw BadRequestException("Missing parameter \"id\"")
                    val issuanceSession = provider.getSession(sessionId)
                        ?: throw NotFoundException("No active issuance session found by the given id")
                    val credentialOffer = issuanceSession.credentialOffer
                        ?: throw BadRequestException("Session has no credential offer set")

                    issuanceSession.callbackUrl?.let {
                        CIProvider.sendCallback(
                            sessionId = sessionId,
                            type = "resolved_credential_offer",
                            data = credentialOffer.toJSON(),
                            callbackUrl = it
                        )
                    }

                    call.respond(credentialOffer.toJSON())
                }

                post("par") {
                    val (_, provider) = resolveTenantProvider(call) ?: return@post
                    val authReq = AuthorizationRequest.fromHttpParameters(call.receiveParameters().toMap())
                    try {
                        val session = provider.initializeIssuanceSession(
                            authorizationRequest = authReq,
                            expiresIn = 5.minutes,
                            authServerState = null
                        )
                        call.respond(
                            PushedAuthorizationResponse.success(
                                requestUri = "${OpenID4VC.PUSHED_AUTHORIZATION_REQUEST_URI_PREFIX}${session.id}",
                                expiresIn = session.expirationTimestamp - Clock.System.now()
                            ).toJSON()
                        )
                    } catch (exc: AuthorizationError) {
                        log.error("Authorization error: ${exc.message}")
                        call.respond(
                            status = HttpStatusCode.BadRequest,
                            message = exc.toPushedAuthorizationErrorResponse().toJSON()
                        )
                    }
                }

                post("token") {
                    val (tenant, provider) = resolveTenantProvider(call) ?: return@post
                    val tokenKey = IssuerTenantRegistry.getTokenKey(tenant)

                    val params = call.receiveParameters().toMap()
                    val tokenReq = TokenRequest.fromHttpParameters(params)

                    // DPoP handling
                    val dpopHeader = call.request.header("DPoP")
                    var dpopThumbprint: String? = null
                    if (dpopHeader != null) {
                        val tokenEndpointUri = "${provider.metadata.issuer}/${call.parameters["standardVersion"]}/token"
                        when (val result = DPoPHandler.validateDPoPProof(
                            dpopProof = dpopHeader,
                            httpMethod = "POST",
                            httpUri = tokenEndpointUri
                        )) {
                            is DPoPHandler.DPoPValidationResult.Success -> {
                                dpopThumbprint = result.thumbprint
                            }
                            is DPoPHandler.DPoPValidationResult.Error -> {
                                call.respond(
                                    status = HttpStatusCode.BadRequest,
                                    message = buildJsonObject {
                                        put("error", JsonPrimitive("invalid_dpop_proof"))
                                        put("error_description", JsonPrimitive(result.message))
                                    }
                                )
                                return@post
                            }
                        }
                    }

                    try {
                        val tokenResp = provider.processTokenRequest(tokenReq, dpopThumbprint, tokenKey)
                        call.respond(tokenResp.toJSON())
                    } catch (exc: TokenError) {
                        log.error("Token error: ${exc.message}")
                        call.respond(
                            status = HttpStatusCode.BadRequest,
                            message = exc.toAuthorizationErrorResponse().toJSON()
                        )
                    }
                }

                post("credential") {
                    val (tenant, provider) = resolveTenantProvider(call) ?: return@post
                    val tokenKey = IssuerTenantRegistry.getTokenKey(tenant)

                    val accessToken = call.request.header(HttpHeaders.Authorization)?.substringAfter(" ")
                    if (accessToken.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                    try {
                        // Verify token with TENANT's token key (not global)
                        val parsedToken = OpenID4VC.verifyAndParseToken(
                            token = accessToken,
                            issuer = provider.metadata.issuer!!,
                            target = TokenTarget.ACCESS,
                            tokenKey = tokenKey
                        )

                        val rawRequest = call.receive<JsonObject>()

                        // Draft 13+ credential request format conversion (same as global OidcApi)
                        val processedRequest = if (!rawRequest.containsKey("format") && rawRequest.containsKey("credential_configuration_id")) {
                            val credConfigId = rawRequest["credential_configuration_id"]?.jsonPrimitive?.content
                            val credConfig = provider.metadata.credentialConfigurationsSupported?.get(credConfigId)
                            val format = credConfig?.format?.value ?: "mso_mdoc"
                            val docType = credConfig?.docType ?: credConfigId
                            val vct = credConfig?.vct

                            buildJsonObject {
                                put("format", JsonPrimitive(format))
                                if (format == "dc+sd-jwt" || format == "vc+sd-jwt") {
                                    rawRequest["vct"]?.let { put("vct", it) } ?: vct?.let { put("vct", JsonPrimitive(it)) }
                                } else {
                                    rawRequest["doctype"]?.let { put("doctype", it) } ?: docType?.let { put("doctype", JsonPrimitive(it)) }
                                }
                                // Convert proofs to proof
                                val proofs = rawRequest["proofs"]?.jsonObject
                                if (proofs != null && !rawRequest.containsKey("proof")) {
                                    val jwtProofs = proofs["jwt"]?.jsonArray
                                    val cwtProofs = proofs["cwt"]?.jsonArray
                                    when {
                                        jwtProofs != null && jwtProofs.isNotEmpty() -> {
                                            put("proof", buildJsonObject {
                                                put("proof_type", JsonPrimitive("jwt"))
                                                put("jwt", jwtProofs[0])
                                            })
                                        }
                                        cwtProofs != null && cwtProofs.isNotEmpty() -> {
                                            put("proof", buildJsonObject {
                                                put("proof_type", JsonPrimitive("cwt"))
                                                put("cwt", cwtProofs[0])
                                            })
                                        }
                                    }
                                }
                                rawRequest.forEach { (key, value) ->
                                    if (key != "credential_configuration_id" && key != "proofs") {
                                        put(key, value)
                                    }
                                }
                            }
                        } else {
                            rawRequest
                        }

                        val credentialRequest = CredentialRequest.fromJSON(processedRequest)

                        val session = parsedToken[JWTClaims.Payload.subject]?.jsonPrimitive?.content?.let {
                            provider.getSession(it)
                        } ?: throw CredentialError(
                            credentialRequest = credentialRequest,
                            errorCode = CredentialErrorCode.invalid_request,
                            message = "Session not found for access token"
                        )

                        // DPoP verification
                        if (session.dpopThumbprint != null) {
                            val dpopHeader = call.request.header("DPoP")
                            if (dpopHeader == null) {
                                call.respond(
                                    status = HttpStatusCode.Unauthorized,
                                    message = buildJsonObject {
                                        put("error", JsonPrimitive("invalid_dpop_proof"))
                                        put("error_description", JsonPrimitive("DPoP proof required for DPoP-bound token"))
                                    }
                                )
                                return@post
                            }
                            val credentialEndpointUri = "${provider.metadata.issuer}/${call.parameters["standardVersion"]}/credential"
                            val accessTokenHash = DPoPHandler.calculateAccessTokenHash(accessToken)
                            when (val result = DPoPHandler.validateDPoPProof(
                                dpopProof = dpopHeader,
                                httpMethod = "POST",
                                httpUri = credentialEndpointUri,
                                accessTokenHash = accessTokenHash
                            )) {
                                is DPoPHandler.DPoPValidationResult.Success -> {
                                    if (result.thumbprint != session.dpopThumbprint) {
                                        call.respond(
                                            status = HttpStatusCode.Unauthorized,
                                            message = buildJsonObject {
                                                put("error", JsonPrimitive("invalid_dpop_proof"))
                                                put("error_description", JsonPrimitive("DPoP key binding mismatch"))
                                            }
                                        )
                                        return@post
                                    }
                                }
                                is DPoPHandler.DPoPValidationResult.Error -> {
                                    call.respond(
                                        status = HttpStatusCode.Unauthorized,
                                        message = buildJsonObject {
                                            put("error", JsonPrimitive("invalid_dpop_proof"))
                                            put("error_description", JsonPrimitive(result.message))
                                        }
                                    )
                                    return@post
                                }
                            }
                        }

                        val credentialResponse = provider.generateCredentialResponse(
                            credentialRequest = credentialRequest,
                            session = session,
                        )

                        // Draft 13+ response format
                        val standardVersion = call.parameters["standardVersion"] ?: "draft13"
                        val responseJson = if (standardVersion.contains("13") || standardVersion.contains("14") || standardVersion.contains("15")) {
                            buildJsonObject {
                                credentialResponse.credential?.let { cred ->
                                    put("credentials", buildJsonArray {
                                        add(buildJsonObject {
                                            put("credential", cred)
                                        })
                                    })
                                }
                                credentialResponse.acceptanceToken?.let {
                                    put("transaction_id", JsonPrimitive(it))
                                }
                                credentialResponse.cNonce?.let { put("c_nonce", JsonPrimitive(it)) }
                                credentialResponse.cNonceExpiresIn?.let { put("c_nonce_expires_in", JsonPrimitive(it.inWholeSeconds.toInt())) }
                            }
                        } else {
                            credentialResponse.toJSON()
                        }

                        call.respond(responseJson)
                    } catch (exc: CredentialError) {
                        log.error("Credential error: ${exc.message}")
                        call.respond(
                            status = HttpStatusCode.BadRequest,
                            message = exc.toCredentialErrorResponse().toJSON()
                        )
                    }
                }

                post("credential_deferred") {
                    val (tenant, provider) = resolveTenantProvider(call) ?: return@post
                    val tokenKey = IssuerTenantRegistry.getTokenKey(tenant)

                    val accessToken = call.request.header(HttpHeaders.Authorization)?.substringAfter(" ")
                    if (accessToken.isNullOrEmpty() || !OpenID4VC.verifyTokenSignature(
                            target = TokenTarget.DEFERRED_CREDENTIAL,
                            token = accessToken,
                            tokenKey = tokenKey
                        )
                    ) {
                        call.respond(HttpStatusCode.Unauthorized)
                    } else {
                        try {
                            call.respond(provider.generateDeferredCredentialResponse(accessToken).toJSON())
                        } catch (exc: DeferredCredentialError) {
                            log.error("DeferredCredentialError: ${exc.message}")
                            call.respond(
                                status = HttpStatusCode.BadRequest,
                                message = exc.toCredentialErrorResponse().toJSON()
                            )
                        }
                    }
                }

                post("batch_credential") {
                    val (tenant, provider) = resolveTenantProvider(call) ?: return@post
                    val tokenKey = IssuerTenantRegistry.getTokenKey(tenant)

                    val accessToken = call.request.header(HttpHeaders.Authorization)?.substringAfter(" ")
                    val parsedToken = accessToken?.let {
                        OpenID4VC.verifyAndParseToken(
                            token = it,
                            issuer = provider.metadata.issuer!!,
                            target = TokenTarget.ACCESS,
                            tokenKey = tokenKey
                        )
                    }
                    if (parsedToken == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                    } else {
                        val req = BatchCredentialRequest.fromJSON(call.receive())
                        try {
                            val session = parsedToken[JWTClaims.Payload.subject]?.jsonPrimitive?.content?.let {
                                provider.getSession(it)
                            } ?: throw BatchCredentialError(
                                batchCredentialRequest = req,
                                errorCode = CredentialErrorCode.invalid_request,
                                errorUri = "Session not found for access token"
                            )
                            call.respond(
                                provider.generateBatchCredentialResponse(
                                    batchCredentialRequest = req,
                                    session = session
                                ).toJSON()
                            )
                        } catch (exc: BatchCredentialError) {
                            log.error("BatchCredentialError: ${exc.message}")
                            call.respond(
                                status = HttpStatusCode.BadRequest,
                                message = exc.toBatchCredentialErrorResponse().toJSON()
                            )
                        }
                    }
                }

                get("authorize") {
                    val (tenant, provider) = resolveTenantProvider(call) ?: return@get
                    val tokenKey = IssuerTenantRegistry.getTokenKey(tenant)
                    val standardVersion = call.parameters["standardVersion"]
                        ?: throw IllegalArgumentException("standardVersion parameter is required")

                    val authReq = runBlocking { AuthorizationRequest.fromHttpParametersAuto(call.parameters.toMap()) }

                    try {
                        // For PAR references, resolve the stored session to get the original auth request
                        val effectiveAuthReq = if (authReq.isReferenceToPAR) {
                            provider.getPushedAuthorizationSession(authReq).authorizationRequest ?: authReq
                        } else {
                            authReq
                        }

                        var issuanceSession = provider.initializeIssuanceSession(
                            authorizationRequest = effectiveAuthReq,
                            expiresIn = 5.minutes,
                            authServerState = null
                        )

                        // For "Add document from list" flow: no credential offer, so issuanceRequests is empty.
                        // Build default requests from authorization_details + tenant config.
                        if (issuanceSession.issuanceRequests.isEmpty() && effectiveAuthReq.authorizationDetails != null) {
                            val configIds = effectiveAuthReq.authorizationDetails!!
                                .mapNotNull { it.credentialConfigurationId }
                            if (configIds.isNotEmpty()) {
                                val defaultRequests = IssuerTenantRegistry.buildDefaultIssuanceRequests(tenant, configIds)
                                issuanceSession = issuanceSession.copy(issuanceRequests = defaultRequests)
                                provider.putSession(issuanceSession.id, issuanceSession, 5.minutes)
                            }
                        }

                        val authMethod = issuanceSession.issuanceRequests.firstOrNull()?.authenticationMethod
                            ?: AuthenticationMethod.PWD

                        val authResp: Any = when {
                            ResponseType.Code in authReq.responseType -> {
                                when (authMethod) {
                                    AuthenticationMethod.PWD -> {
                                        val issuerConfig = ConfigManager.getConfig<OIDCIssuerServiceConfig>()
                                        val globalBaseUrl = issuerConfig.externalBaseUrl ?: issuerConfig.baseUrl
                                        call.response.apply {
                                            status(HttpStatusCode.Found)
                                            header(
                                                name = HttpHeaders.Location,
                                                value = "${globalBaseUrl}/pre_login/${authReq.toHttpQueryString()}&_tenantId=${tenant.id}&_tenantSessionId=${issuanceSession.id}"
                                            )
                                        }
                                        return@get
                                    }
                                    AuthenticationMethod.NONE -> OpenID4VC.processCodeFlowAuthorization(
                                        authorizationRequest = authReq,
                                        sessionId = issuanceSession.id,
                                        providerMetadata = provider.metadata,
                                        tokenKey = tokenKey
                                    )
                                    else -> throw AuthorizationError(
                                        authorizationRequest = authReq,
                                        errorCode = AuthorizationErrorCode.invalid_request,
                                        message = "Request Authentication Method is invalid"
                                    )
                                }
                            }
                            ResponseType.Token in authReq.responseType -> OpenID4VC.processImplicitFlowAuthorization(
                                authorizationRequest = authReq,
                                sessionId = issuanceSession.id,
                                providerMetadata = provider.metadata,
                                tokenKey = tokenKey
                            )
                            else -> throw AuthorizationError(
                                authorizationRequest = authReq,
                                errorCode = AuthorizationErrorCode.unsupported_response_type,
                                message = "Response type not supported"
                            )
                        }

                        val redirectUri = if (authReq.isReferenceToPAR) {
                            val pushedSession = provider.getPushedAuthorizationSession(authReq)
                            pushedSession.authorizationRequest?.redirectUri
                        } else {
                            authReq.redirectUri
                        } ?: throw AuthorizationError(
                            authorizationRequest = authReq,
                            errorCode = AuthorizationErrorCode.invalid_request,
                            message = "No redirect_uri found for this authorization request"
                        )

                        call.response.apply {
                            status(HttpStatusCode.Found)
                            val defaultResponseMode =
                                if (authReq.responseType.contains(ResponseType.Code)) ResponseMode.query else ResponseMode.fragment
                            authResp as IHTTPDataObject
                            header(
                                name = HttpHeaders.Location,
                                value = authResp.toRedirectUri(
                                    redirectUri = redirectUri,
                                    responseMode = authReq.responseMode ?: defaultResponseMode
                                )
                            )
                        }
                    } catch (authExc: AuthorizationError) {
                        log.error("Authorization error: ${authExc.message}")
                        call.response.apply {
                            status(HttpStatusCode.Found)
                            header(
                                name = HttpHeaders.Location,
                                value = URLBuilder(authExc.authorizationRequest.redirectUri!!).apply {
                                    parameters.appendAll(
                                        parametersOf(authExc.toAuthorizationErrorResponse().toHttpParameters())
                                    )
                                }.buildString()
                            )
                        }
                    }
                }

                post("direct_post") {
                    val (tenant, provider) = resolveTenantProvider(call) ?: return@post
                    val tokenKey = IssuerTenantRegistry.getTokenKey(tenant)

                    val params = call.receiveParameters().toMap()

                    if (params["state"]?.get(0) == null ||
                        (params[ResponseType.IdToken.value]?.get(0) == null &&
                            params[ResponseType.VpToken.value]?.get(0) == null)
                    ) {
                        call.respond(
                            status = HttpStatusCode.BadRequest,
                            message = "missing state/id_token/vp_token parameter"
                        )
                        return@post
                    }

                    try {
                        val state = params["state"]?.get(0)!!
                        if (params[ResponseType.IdToken.value]?.get(0) != null) {
                            val idToken = params[ResponseType.IdToken.value]?.get(0)!!
                            OpenID4VC.verifyAndParseIdToken(idToken)
                        } else {
                            val vpToken = params[ResponseType.VpToken.value]?.get(0)!!
                            val presSub = params["presentation_submission"]?.get(0)!!
                            val presentationFormat =
                                PresentationSubmission.fromJSONString(presSub).descriptorMap.firstOrNull()?.format
                                    ?: throw IllegalArgumentException("No presentation submission or presentation format found.")
                            val policies =
                                Json.parseToJsonElement("""["signature", "expired", "not-before"]""").jsonArray.parsePolicyRequests()
                            Verifier.verifyPresentation(
                                format = presentationFormat,
                                vpToken = vpToken,
                                vpPolicies = policies,
                                globalVcPolicies = policies,
                                specificCredentialPolicies = emptyMap(),
                                presentationContext = mapOf("presentationSubmission" to presSub)
                            )
                        }

                        val session = provider.getSessionByAuthServerState(state)
                            ?: throw IllegalStateException("No session found for given state parameter")
                        val resp = OpenID4VC.processDirectPost(
                            authorizationRequest = session.authorizationRequest
                                ?: throw IllegalStateException("Session for given state has no authorization request"),
                            sessionId = session.id,
                            providerMetadata = provider.metadata,
                            tokenKey = tokenKey
                        )

                        val redirectUri = provider.getSessionByAuthServerState(state)!!.authorizationRequest!!.redirectUri!!
                        call.response.apply {
                            status(HttpStatusCode.Found)
                            header(
                                name = HttpHeaders.Location,
                                value = resp.toRedirectUri(
                                    redirectUri = redirectUri,
                                    responseMode = ResponseMode.query
                                )
                            )
                        }
                    } catch (exc: TokenError) {
                        call.respond(
                            status = HttpStatusCode.BadRequest,
                            message = exc.toAuthorizationErrorResponse().toJSON()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resolves the tenant from the route path, validates status and key presence.
 * Returns null (and responds with error) if tenant cannot be resolved.
 */
private suspend fun resolveTenantProvider(call: ApplicationCall): Pair<IssuerTenant, CIProvider>? {
    val issuerId = call.parameters["issuerId"]
        ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing issuerId"))
            return null
        }

    val store = IssuerTenantStore.instanceOrNull()
        ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issuer Registrar not available"))
            return null
        }

    val tenant = store.get(issuerId)
        ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown issuer: $issuerId"))
            return null
        }

    if (tenant.status != IssuerTenantStatus.ACTIVE) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Issuer is ${tenant.status}")
        )
        return null
    }

    if (tenant.issuerKey == null || tenant.ciTokenKey == null) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Issuer has no signing keys configured. Generate or upload certificates first.")
        )
        return null
    }

    val provider = IssuerTenantRegistry.getOrCreate(tenant)
    return tenant to provider
}
