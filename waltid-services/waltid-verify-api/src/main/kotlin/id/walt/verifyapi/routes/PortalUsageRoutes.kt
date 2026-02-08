package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.AUTH_PORTAL_JWT
import id.walt.verifyapi.portal.PortalUserPrincipal
import id.walt.verifyapi.service.UsageTrackingService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

/**
 * Portal usage analytics routes.
 *
 * Provides endpoints for viewing verification usage statistics.
 * All endpoints require portal JWT authentication.
 */
fun Route.portalUsageRoutes() {
    authenticate(AUTH_PORTAL_JWT) {
        route("/portal/usage") {
            /**
             * GET /portal/usage
             *
             * Get usage analytics for the authenticated organization.
             *
             * Query Parameters:
             * - start_date: Optional start date (YYYY-MM-DD) for time range filter
             * - end_date: Optional end date (YYYY-MM-DD) for time range filter
             * - environment: Optional filter by "live" or "test"
             *
             * Returns aggregated usage statistics including:
             * - Period counts (today, this week, this month, all time)
             * - Status breakdown (verified, failed, expired, pending)
             * - Template breakdown (count per template)
             * - Environment breakdown (live vs test)
             */
            get {
                val principal = call.principal<PortalUserPrincipal>()!!
                val organizationId = principal.organizationId

                logger.debug { "Getting usage analytics for organization: $organizationId" }

                // Parse optional date filters
                val startDate = call.request.queryParameters["start_date"]?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (e: DateTimeParseException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid start_date format. Use YYYY-MM-DD")
                        )
                        return@get
                    }
                }

                val endDate = call.request.queryParameters["end_date"]?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (e: DateTimeParseException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid end_date format. Use YYYY-MM-DD")
                        )
                        return@get
                    }
                }

                // Validate date range
                if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "start_date must be before or equal to end_date")
                    )
                    return@get
                }

                // Parse optional environment filter
                val environment = call.request.queryParameters["environment"]?.let { env ->
                    if (env !in listOf("live", "test")) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "environment must be 'live' or 'test'")
                        )
                        return@get
                    }
                    env
                }

                val analytics = UsageTrackingService.getUsageAnalytics(
                    organizationId = organizationId,
                    startDate = startDate,
                    endDate = endDate,
                    environment = environment
                )

                call.respond(analytics)
            }

            /**
             * GET /portal/usage/daily
             *
             * Get daily usage counts for charting.
             *
             * Query Parameters:
             * - days: Number of days to include (default 30, max 365)
             * - environment: Optional filter by "live" or "test"
             *
             * Returns an array of { date, count } objects for each day.
             */
            get("/daily") {
                val principal = call.principal<PortalUserPrincipal>()!!
                val organizationId = principal.organizationId

                logger.debug { "Getting daily usage for organization: $organizationId" }

                // Parse days parameter
                val days = call.request.queryParameters["days"]?.let { daysStr ->
                    val parsed = daysStr.toIntOrNull()
                    if (parsed == null || parsed < 1 || parsed > 365) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "days must be an integer between 1 and 365")
                        )
                        return@get
                    }
                    parsed
                } ?: 30

                // Parse optional environment filter
                val environment = call.request.queryParameters["environment"]?.let { env ->
                    if (env !in listOf("live", "test")) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "environment must be 'live' or 'test'")
                        )
                        return@get
                    }
                    env
                }

                val dailyUsage = UsageTrackingService.getDailyUsage(
                    organizationId = organizationId,
                    days = days,
                    environment = environment
                )

                call.respond(dailyUsage)
            }
        }
    }
}
