package de.mhus.vance.shared.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizedTextsTest {

    @Test
    void prefers_requested_language() {
        Map<String, String> m = Map.of("de", "Hallo", "en", "Hello");
        assertThat(LocalizedTexts.resolve(m, "de")).isEqualTo("Hallo");
    }

    @Test
    void fallsBack_to_english_whenPreferredMissing() {
        Map<String, String> m = Map.of("de", "Hallo", "en", "Hello");
        assertThat(LocalizedTexts.resolve(m, "fr")).isEqualTo("Hello");
    }

    @Test
    void fallsBack_toFirstAvailable_whenEnglishMissing() {
        // LinkedHashMap to control iteration order — first non-blank wins.
        Map<String, String> m = new LinkedHashMap<>();
        m.put("de", "Hallo");
        m.put("fr", "Bonjour");
        assertThat(LocalizedTexts.resolve(m, "es")).isEqualTo("Hallo");
    }

    @Test
    void returns_empty_forEmpty_or_null_map() {
        assertThat(LocalizedTexts.resolve(null, "de")).isEmpty();
        assertThat(LocalizedTexts.resolve(Map.of(), "de")).isEmpty();
    }

    @Test
    void ignores_blank_entries() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("de", "  ");
        m.put("en", "Hello");
        assertThat(LocalizedTexts.resolve(m, "de")).isEqualTo("Hello");
    }
}
