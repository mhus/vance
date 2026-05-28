package de.mhus.vance.brain.tools.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Calendar;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class PdfMetadataToolTest {

    @Test
    void formatDate_nullReturnsNull() {
        assertThat(PdfMetadataTool.formatDate(null)).isNull();
    }

    @Test
    void formatDate_returnsIso8601Utc() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(2025, Calendar.JANUARY, 15, 10, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);

        String iso = PdfMetadataTool.formatDate(cal);
        assertThat(iso).isEqualTo("2025-01-15T10:30:00Z");
    }

    @Test
    void formatDate_convertsNonUtcTimezone() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin"));
        // 12:00 CET = 11:00 UTC
        cal.set(2025, Calendar.JANUARY, 15, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);

        String iso = PdfMetadataTool.formatDate(cal);
        assertThat(iso).isEqualTo("2025-01-15T11:00:00Z");
    }
}
