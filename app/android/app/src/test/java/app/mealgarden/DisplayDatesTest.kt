package app.mealgarden

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DisplayDatesTest {
    @Test fun timestampsUseHouseholdDayAndDateOnlyReceiptsKeepTheirDay() {
        val zone = ZoneId.of("Pacific/Honolulu")
        val today = LocalDate.of(2031, 2, 10)
        assertEquals("Yesterday", humanDate("2031-02-10T01:00:00Z", zone, today))
        assertEquals("Today", humanDate("2031-02-10", zone, today))
        assertEquals("Feb 8", humanDate("2031-02-08", zone, today))
        assertEquals("Dec 30, 2030", humanDate("2030-12-30", zone, today))
        assertEquals("Date unknown", humanDate("not a date", zone, today))
    }
}
