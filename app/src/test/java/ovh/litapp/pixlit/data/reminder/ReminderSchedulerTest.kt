package ovh.litapp.pixlit.data.reminder

import java.time.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderSchedulerTest {
    @Test fun `next run rolls to next week after scheduled time`() {
        val now = ZonedDateTime.of(2026, 9, 4, 21, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals(Duration.ofDays(6).plusHours(23), nextRunDelay(DayOfWeek.FRIDAY, LocalTime.of(20, 0), now))
    }

    @Test fun `next run is same day before scheduled time`() {
        val now = ZonedDateTime.of(2026, 9, 4, 19, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals(Duration.ofHours(1), nextRunDelay(DayOfWeek.FRIDAY, LocalTime.of(20, 0), now))
    }
}
