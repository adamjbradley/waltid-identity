package id.walt.verifyapi

import id.walt.verifyapi.service.DailyCount
import id.walt.verifyapi.service.DateRange
import id.walt.verifyapi.service.EnvironmentBreakdown
import id.walt.verifyapi.service.PeriodCounts
import id.walt.verifyapi.service.StatusBreakdown
import id.walt.verifyapi.service.TemplateUsage
import id.walt.verifyapi.service.UsageAnalytics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for UsageTrackingService data models.
 * Database integration tests would require a test database setup.
 */
class UsageTrackingServiceTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun `test UsageAnalytics serialization`() {
        val analytics = UsageAnalytics(
            periodCounts = PeriodCounts(
                today = 5,
                thisWeek = 25,
                thisMonth = 100,
                allTime = 500
            ),
            statusBreakdown = StatusBreakdown(
                verified = 80,
                failed = 10,
                expired = 5,
                pending = 5
            ),
            templateBreakdown = listOf(
                TemplateUsage("age_check", 50),
                TemplateUsage("full_kyc", 30),
                TemplateUsage("basic_identity", 20)
            ),
            environmentBreakdown = EnvironmentBreakdown(
                live = 90,
                test = 10
            ),
            dateRange = DateRange(
                start = "2024-01-01",
                end = "2024-01-31"
            )
        )

        val serialized = json.encodeToString(analytics)
        assertNotNull(serialized)
        assertTrue(serialized.contains("\"today\": 5"))
        assertTrue(serialized.contains("\"verified\": 80"))
        assertTrue(serialized.contains("\"age_check\""))
        assertTrue(serialized.contains("\"live\": 90"))
    }

    @Test
    fun `test PeriodCounts all values are non-negative`() {
        val counts = PeriodCounts(
            today = 0,
            thisWeek = 0,
            thisMonth = 0,
            allTime = 0
        )

        assertTrue(counts.today >= 0)
        assertTrue(counts.thisWeek >= 0)
        assertTrue(counts.thisMonth >= 0)
        assertTrue(counts.allTime >= 0)
    }

    @Test
    fun `test StatusBreakdown sums correctly`() {
        val breakdown = StatusBreakdown(
            verified = 80,
            failed = 10,
            expired = 5,
            pending = 5
        )

        val total = breakdown.verified + breakdown.failed + breakdown.expired + breakdown.pending
        assertEquals(100, total)
    }

    @Test
    fun `test TemplateUsage list can be sorted by count`() {
        val templates = listOf(
            TemplateUsage("template_a", 10),
            TemplateUsage("template_b", 50),
            TemplateUsage("template_c", 25)
        )

        val sorted = templates.sortedByDescending { it.count }

        assertEquals("template_b", sorted[0].templateName)
        assertEquals("template_c", sorted[1].templateName)
        assertEquals("template_a", sorted[2].templateName)
    }

    @Test
    fun `test DailyCount serialization`() {
        val dailyCounts = listOf(
            DailyCount("2024-01-01", 10),
            DailyCount("2024-01-02", 15),
            DailyCount("2024-01-03", 8)
        )

        val serialized = json.encodeToString(dailyCounts)
        assertNotNull(serialized)
        assertTrue(serialized.contains("\"2024-01-01\""))
        assertTrue(serialized.contains("10"))
    }

    @Test
    fun `test DateRange with null values`() {
        val rangeWithNulls = DateRange(start = null, end = null)
        val serialized = json.encodeToString(rangeWithNulls)

        assertTrue(serialized.contains("\"start\": null"))
        assertTrue(serialized.contains("\"end\": null"))
    }

    @Test
    fun `test DateRange with values`() {
        val today = LocalDate.now().toString()
        val lastWeek = LocalDate.now().minusDays(7).toString()

        val range = DateRange(start = lastWeek, end = today)

        assertEquals(lastWeek, range.start)
        assertEquals(today, range.end)
    }

    @Test
    fun `test EnvironmentBreakdown totals`() {
        val breakdown = EnvironmentBreakdown(live = 90, test = 10)

        assertEquals(100, breakdown.live + breakdown.test)
    }

    @Test
    fun `test empty UsageAnalytics`() {
        val emptyAnalytics = UsageAnalytics(
            periodCounts = PeriodCounts(0, 0, 0, 0),
            statusBreakdown = StatusBreakdown(0, 0, 0, 0),
            templateBreakdown = emptyList(),
            environmentBreakdown = EnvironmentBreakdown(0, 0),
            dateRange = DateRange(null, null)
        )

        assertEquals(0, emptyAnalytics.periodCounts.allTime)
        assertTrue(emptyAnalytics.templateBreakdown.isEmpty())
    }

    @Test
    fun `test UsageAnalytics JSON structure`() {
        val analytics = UsageAnalytics(
            periodCounts = PeriodCounts(1, 7, 30, 100),
            statusBreakdown = StatusBreakdown(90, 5, 3, 2),
            templateBreakdown = listOf(TemplateUsage("test", 100)),
            environmentBreakdown = EnvironmentBreakdown(80, 20),
            dateRange = DateRange("2024-01-01", "2024-01-31")
        )

        val serialized = json.encodeToString(analytics)

        // Verify JSON structure contains expected keys
        assertTrue(serialized.contains("\"periodCounts\""))
        assertTrue(serialized.contains("\"statusBreakdown\""))
        assertTrue(serialized.contains("\"templateBreakdown\""))
        assertTrue(serialized.contains("\"environmentBreakdown\""))
        assertTrue(serialized.contains("\"dateRange\""))
    }
}
