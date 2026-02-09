package id.walt.openid4vp.verifier.trust

import id.walt.commons.trust.TrustListServiceFactory
import id.walt.trust.TrustServiceStatus
import id.walt.trust.TrustSource
import id.walt.trust.models.TrustServiceEntry
import id.walt.trust.models.TrustServiceList
import id.walt.trust.models.TrustServiceProvider
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Application.trustAdminRoutes() {
    routing {
        route("/admin/trust") {
            get("/status") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                val status = service.getStatus()
                call.respond(Json.encodeToJsonElement(TrustServiceStatus.serializer(), status))
            }

            put("/etsi") {
                val body = call.receive<ToggleRequest>()
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@put call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                service.setEnabled(TrustSource.ETSI_TL, body.enabled)
                call.respond(HttpStatusCode.OK, mapOf("source" to "etsi_tl", "enabled" to body.enabled.toString()))
            }

            put("/federation") {
                val body = call.receive<ToggleRequest>()
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@put call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                service.setEnabled(TrustSource.OPENID_FEDERATION, body.enabled)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("source" to "openid_federation", "enabled" to body.enabled.toString())
                )
            }

            post("/refresh") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                // Re-fetch trust lists
                val status = service.getStatus()
                call.respond(Json.encodeToJsonElement(TrustServiceStatus.serializer(), status))
            }

            // -- LOTL Browsing endpoints --

            get("/lotl") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )

                val lotl = service.getLotl()
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "LOTL data not available. Try refreshing trust lists.")
                    )

                val memberStateTls = service.getMemberStateTls()

                val memberStates = lotl.pointers.map { pointer ->
                    val country = pointer.schemeTerritory ?: "??"
                    val tsl = memberStateTls[country]
                    MemberStateSummary(
                        country = country,
                        location = pointer.location,
                        providerCount = tsl?.trustServiceProviders?.size ?: 0,
                        serviceCount = tsl?.trustServiceProviders?.sumOf { it.trustServices.size } ?: 0,
                        healthy = tsl != null
                    )
                }

                call.respond(LotlOverview(
                    schemeTerritory = lotl.schemeTerritory,
                    schemeOperatorName = lotl.schemeOperatorName,
                    listIssueDate = lotl.listIssueDate?.toString(),
                    nextUpdate = lotl.nextUpdate?.toString(),
                    sequenceNumber = lotl.sequenceNumber,
                    memberStates = memberStates
                ))
            }

            get("/lotl/{country}") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )

                val country = call.parameters["country"]?.uppercase()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Country code required"))

                val tsl = service.getMemberStateTl(country)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "No trust list found for country: $country")
                    )

                call.respond(CountryTslDetail(
                    schemeTerritory = tsl.schemeTerritory,
                    schemeOperatorName = tsl.schemeOperatorName,
                    listIssueDate = tsl.listIssueDate?.toString(),
                    nextUpdate = tsl.nextUpdate?.toString(),
                    sequenceNumber = tsl.sequenceNumber,
                    providers = tsl.trustServiceProviders.map { provider ->
                        ProviderDetail(
                            name = provider.name,
                            tradeName = provider.tradeName,
                            services = provider.trustServices.map { service ->
                                ServiceDetail(
                                    serviceName = service.serviceName,
                                    serviceType = service.serviceType,
                                    serviceTypeLabel = humanReadableServiceType(service.serviceType),
                                    status = humanReadableStatus(service.currentStatus),
                                    statusRaw = service.currentStatus,
                                    statusStartingTime = service.statusStartingTime?.toString(),
                                    isQualified = service.isQualified,
                                    x509SubjectName = service.serviceDigitalIdentity?.x509SubjectName,
                                    x509Certificate = service.serviceDigitalIdentity?.x509Certificate
                                )
                            }
                        )
                    }
                ))
            }

            get("/search") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )

                val query = call.request.queryParameters["q"]
                val country = call.request.queryParameters["country"]
                val status = call.request.queryParameters["status"]
                val serviceType = call.request.queryParameters["type"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val providers = service.searchProviders(
                    query = query,
                    country = country,
                    status = status,
                    serviceType = serviceType,
                    limit = limit.coerceIn(1, 200),
                    offset = offset.coerceAtLeast(0)
                )

                call.respond(SearchResponse(
                    query = query,
                    country = country,
                    status = status,
                    serviceType = serviceType,
                    total = providers.size,
                    providers = providers.map { provider ->
                        ProviderDetail(
                            name = provider.name,
                            tradeName = provider.tradeName,
                            country = provider.country,
                            services = provider.trustServices.map { svc ->
                                ServiceDetail(
                                    serviceName = svc.serviceName,
                                    serviceType = svc.serviceType,
                                    serviceTypeLabel = humanReadableServiceType(svc.serviceType),
                                    status = humanReadableStatus(svc.currentStatus),
                                    statusRaw = svc.currentStatus,
                                    statusStartingTime = svc.statusStartingTime?.toString(),
                                    isQualified = svc.isQualified,
                                    x509SubjectName = svc.serviceDigitalIdentity?.x509SubjectName,
                                    x509Certificate = svc.serviceDigitalIdentity?.x509Certificate
                                )
                            }
                        )
                    }
                ))
            }

            // -- Custom TSL Import endpoints --

            get("/custom-tsls") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )

                val customUrls = service.getCustomTslUrls()
                val memberStateTls = service.getMemberStateTls()

                val entries = customUrls.map { (country, url) ->
                    val tsl = memberStateTls[country]
                    CustomTslEntry(
                        country = country,
                        url = url,
                        providerCount = tsl?.trustServiceProviders?.size ?: 0,
                        serviceCount = tsl?.trustServiceProviders?.sumOf { it.trustServices.size } ?: 0,
                        loaded = tsl != null
                    )
                }

                call.respond(CustomTslListResponse(customTsls = entries))
            }

            post("/custom-tsls") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )

                val body = call.receive<CustomTslImportRequest>()
                val country = body.country.uppercase()

                try {
                    val tsl = service.addCustomTsl(country, body.url)
                    call.respond(HttpStatusCode.Created, CustomTslImportResponse(
                        country = country,
                        url = body.url,
                        schemeTerritory = tsl.schemeTerritory,
                        schemeOperatorName = tsl.schemeOperatorName,
                        providerCount = tsl.trustServiceProviders.size,
                        serviceCount = tsl.trustServiceProviders.sumOf { it.trustServices.size }
                    ))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Failed to import TSL: ${e.message}")
                    )
                }
            }

            delete("/custom-tsls/{country}") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@delete call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )

                val country = call.parameters["country"]?.uppercase()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Country code required"))

                val removed = service.removeCustomTsl(country)
                if (removed) {
                    call.respond(HttpStatusCode.OK, mapOf("removed" to country))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No custom TSL found for country: $country"))
                }
            }
        }
    }
}

// -- DTOs --

@Serializable
data class ToggleRequest(val enabled: Boolean)

@Serializable
data class MemberStateSummary(
    val country: String,
    val location: String,
    val providerCount: Int,
    val serviceCount: Int,
    val healthy: Boolean
)

@Serializable
data class LotlOverview(
    val schemeTerritory: String,
    val schemeOperatorName: String,
    val listIssueDate: String? = null,
    val nextUpdate: String? = null,
    val sequenceNumber: Int? = null,
    val memberStates: List<MemberStateSummary>
)

@Serializable
data class CountryTslDetail(
    val schemeTerritory: String,
    val schemeOperatorName: String,
    val listIssueDate: String? = null,
    val nextUpdate: String? = null,
    val sequenceNumber: Int? = null,
    val providers: List<ProviderDetail>
)

@Serializable
data class ProviderDetail(
    val name: String,
    val tradeName: String? = null,
    val country: String? = null,
    val services: List<ServiceDetail>
)

@Serializable
data class ServiceDetail(
    val serviceName: String,
    val serviceType: String,
    val serviceTypeLabel: String,
    val status: String,
    val statusRaw: String,
    val statusStartingTime: String? = null,
    val isQualified: Boolean,
    val x509SubjectName: String? = null,
    val x509Certificate: String? = null
)

@Serializable
data class SearchResponse(
    val query: String? = null,
    val country: String? = null,
    val status: String? = null,
    val serviceType: String? = null,
    val total: Int,
    val providers: List<ProviderDetail>
)

@Serializable
data class CustomTslImportRequest(
    val country: String,
    val url: String
)

@Serializable
data class CustomTslEntry(
    val country: String,
    val url: String,
    val providerCount: Int,
    val serviceCount: Int,
    val loaded: Boolean
)

@Serializable
data class CustomTslListResponse(
    val customTsls: List<CustomTslEntry>
)

@Serializable
data class CustomTslImportResponse(
    val country: String,
    val url: String,
    val schemeTerritory: String,
    val schemeOperatorName: String,
    val providerCount: Int,
    val serviceCount: Int
)

// -- Helpers --

private fun humanReadableServiceType(typeUri: String): String = when {
    typeUri.contains("CA/QC") -> "CA/QC"
    typeUri.contains("QESVal") -> "QES Validation"
    typeUri.contains("TSA") -> "Timestamp Authority"
    typeUri.contains("EDS") -> "Electronic Delivery"
    typeUri.contains("PSES") -> "Preservation Service"
    typeUri.contains("REM") -> "Registered e-Mail"
    typeUri.contains("AdESVal") -> "AdES Validation"
    typeUri.contains("AdESGen") -> "AdES Generation"
    else -> typeUri.substringAfterLast("/")
}

private fun humanReadableStatus(statusUri: String): String = when {
    statusUri.contains("granted") -> "granted"
    statusUri.contains("withdrawn") -> "withdrawn"
    statusUri.contains("deprecatedatnationallevel") -> "deprecated"
    statusUri.contains("recognisedatnationallevel") -> "recognised"
    else -> statusUri.substringAfterLast("/")
}
