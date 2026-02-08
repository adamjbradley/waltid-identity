@file:OptIn(ExperimentalTime::class)

package id.walt.verifyapi.service

import id.walt.verifyapi.db.VerifyUsageEvents
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Service for tracking and analyzing verification usage.
 * Records all verification events and provides aggregated analytics.
 */
object UsageTrackingService {

    /**
     * Record a new verification event when a session is created.
     */
    fun recordVerificationStarted(
        organizationId: UUID,
        sessionId: UUID,
        templateName: String,
        environment: String
    ): UUID {
        val eventId = transaction {
            VerifyUsageEvents.insert {
                it[this.organizationId] = organizationId
                it[this.sessionId] = sessionId
                it[this.templateName] = templateName
                it[this.environment] = environment
                it[status] = "pending"
                it[createdAt] = Instant.now()
            } get VerifyUsageEvents.id
        }

        logger.debug { "Recorded usage event $eventId for org $organizationId, template $templateName" }
        return eventId.value
    }

    /**
     * Update a verification event when the session completes.
     */
    fun recordVerificationCompleted(
        sessionId: UUID,
        status: String
    ) {
        transaction {
            VerifyUsageEvents.update({ VerifyUsageEvents.sessionId eq sessionId }) {
                it[this.status] = status
                it[completedAt] = Instant.now()
            }
        }
        logger.debug { "Updated usage event for session $sessionId with status $status" }
    }

    /**
     * Get usage analytics for an organization.
     *
     * @param organizationId The organization to get analytics for
     * @param startDate Optional start date for the time range (inclusive)
     * @param endDate Optional end date for the time range (inclusive)
     * @param environment Optional filter by environment ("live" or "test")
     */
    fun getUsageAnalytics(
        organizationId: UUID,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        environment: String? = null
    ): UsageAnalytics {
        val now = Instant.now()
        val todayStart = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC)
        val weekStart = LocalDate.now().minusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC)
        val monthStart = LocalDate.now().minusDays(30).atStartOfDay().toInstant(ZoneOffset.UTC)

        return transaction {
            // Apply time range filter if provided
            val rangeStartInstant = startDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)
            val rangeEndInstant = endDate?.plusDays(1)?.atStartOfDay()?.toInstant(ZoneOffset.UTC)

            // Count functions with optional filters using combined where clause
            fun countWithFilters(
                start: Instant? = rangeStartInstant,
                end: Instant? = rangeEndInstant,
                statusFilter: String? = null,
                envFilter: String? = environment
            ): Long {
                // Build a combined condition
                var condition = VerifyUsageEvents.organizationId eq organizationId

                start?.let { s ->
                    condition = condition and (VerifyUsageEvents.createdAt greaterEq s)
                }
                end?.let { e ->
                    condition = condition and (VerifyUsageEvents.createdAt less e)
                }
                statusFilter?.let { st ->
                    condition = condition and (VerifyUsageEvents.status eq st)
                }
                envFilter?.let { env ->
                    condition = condition and (VerifyUsageEvents.environment eq env)
                }

                return VerifyUsageEvents.selectAll()
                    .where { condition }
                    .count()
            }

            // Period counts (today, week, month, all time)
            val todayTotal = countWithFilters(todayStart, null)
            val weekTotal = countWithFilters(weekStart, null)
            val monthTotal = countWithFilters(monthStart, null)
            val allTimeTotal = countWithFilters(null, null)

            // Status breakdown for the filtered period
            val verified = countWithFilters(statusFilter = "verified")
            val failed = countWithFilters(statusFilter = "failed")
            val expired = countWithFilters(statusFilter = "expired")
            val pending = countWithFilters(statusFilter = "pending")

            // Template breakdown for the filtered period
            val templateBreakdown = run {
                // Build a combined condition
                var condition = VerifyUsageEvents.organizationId eq organizationId

                rangeStartInstant?.let { s ->
                    condition = condition and (VerifyUsageEvents.createdAt greaterEq s)
                }
                rangeEndInstant?.let { e ->
                    condition = condition and (VerifyUsageEvents.createdAt less e)
                }
                environment?.let { env ->
                    condition = condition and (VerifyUsageEvents.environment eq env)
                }

                VerifyUsageEvents.selectAll()
                    .where { condition }
                    .toList()
                    .groupBy { it[VerifyUsageEvents.templateName] }
                    .mapValues { (_, rows) -> rows.size.toLong() }
                    .map { (name, count) ->
                        TemplateUsage(templateName = name, count = count)
                    }
                    .sortedByDescending { it.count }
            }

            // Environment breakdown
            val liveCount = countWithFilters(envFilter = "live")
            val testCount = countWithFilters(envFilter = "test")

            UsageAnalytics(
                periodCounts = PeriodCounts(
                    today = todayTotal,
                    thisWeek = weekTotal,
                    thisMonth = monthTotal,
                    allTime = allTimeTotal
                ),
                statusBreakdown = StatusBreakdown(
                    verified = verified,
                    failed = failed,
                    expired = expired,
                    pending = pending
                ),
                templateBreakdown = templateBreakdown,
                environmentBreakdown = EnvironmentBreakdown(
                    live = liveCount,
                    test = testCount
                ),
                dateRange = DateRange(
                    start = startDate?.toString(),
                    end = endDate?.toString()
                )
            )
        }
    }

    /**
     * Get daily usage counts for a time range (for charting).
     */
    fun getDailyUsage(
        organizationId: UUID,
        days: Int = 30,
        environment: String? = null
    ): List<DailyCount> {
        val startDate = LocalDate.now().minusDays(days.toLong() - 1)
        val startInstant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC)

        return transaction {
            // Build a combined condition
            var condition = (VerifyUsageEvents.organizationId eq organizationId) and
                    (VerifyUsageEvents.createdAt greaterEq startInstant)

            environment?.let { env ->
                condition = condition and (VerifyUsageEvents.environment eq env)
            }

            val events = VerifyUsageEvents.selectAll()
                .where { condition }
                .toList()

            // Group by date - convert Instant to LocalDate string
            val countsByDate = events.groupBy { row ->
                val instant = row[VerifyUsageEvents.createdAt]
                LocalDate.ofInstant(instant, ZoneOffset.UTC).toString()
            }.mapValues { (_, rows) -> rows.size.toLong() }

            // Fill in missing dates with zeros
            (0 until days).map { offset ->
                val date = startDate.plusDays(offset.toLong()).toString()
                DailyCount(date = date, count = countsByDate[date] ?: 0)
            }
        }
    }
}

@Serializable
data class UsageAnalytics(
    val periodCounts: PeriodCounts,
    val statusBreakdown: StatusBreakdown,
    val templateBreakdown: List<TemplateUsage>,
    val environmentBreakdown: EnvironmentBreakdown,
    val dateRange: DateRange
)

@Serializable
data class PeriodCounts(
    val today: Long,
    val thisWeek: Long,
    val thisMonth: Long,
    val allTime: Long
)

@Serializable
data class StatusBreakdown(
    val verified: Long,
    val failed: Long,
    val expired: Long,
    val pending: Long
)

@Serializable
data class TemplateUsage(
    val templateName: String,
    val count: Long
)

@Serializable
data class EnvironmentBreakdown(
    val live: Long,
    val test: Long
)

@Serializable
data class DateRange(
    val start: String?,
    val end: String?
)

@Serializable
data class DailyCount(
    val date: String,
    val count: Long
)
