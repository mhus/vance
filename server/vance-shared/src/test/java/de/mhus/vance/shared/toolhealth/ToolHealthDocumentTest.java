package de.mhus.vance.shared.toolhealth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolHealthDocumentTest {

    // Spring Data MongoDB writes fields via reflection and can leave the
    // collection slots at null when the BSON has the key missing or null.
    // The getters must lazy-init so AgrajagChecker / ToolHealthService can
    // iterate and mutate without a defensive null-check.

    @Test
    void getCooldowns_nullField_returnsEmptyMutableListAndPopulatesField() {
        ToolHealthDocument doc = new ToolHealthDocument();
        doc.setCooldowns(null);

        var cooldowns = doc.getCooldowns();

        assertThat(cooldowns).isNotNull().isEmpty();
        // Field is now backed by a real list — subsequent .add() goes
        // through to the same instance the next getCooldowns() returns.
        cooldowns.add(ToolHealthCooldown.builder().errorSignature("sig").build());
        assertThat(doc.getCooldowns()).hasSize(1);
    }

    @Test
    void getHistory_nullField_returnsEmptyMutableListAndPopulatesField() {
        ToolHealthDocument doc = new ToolHealthDocument();
        doc.setHistory(null);

        var history = doc.getHistory();

        assertThat(history).isNotNull().isEmpty();
        history.add(ToolHealthHistoryEntry.builder().by("test").build());
        assertThat(doc.getHistory()).hasSize(1);
    }
}
