package de.mhus.vance.shared.lease;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Taking a counter key apart, and which tenant a counter belongs to. */
class LeaseCounterTest {

    private static final Instant AT = Instant.parse("2026-10-15T08:00:00Z");

    private static LeaseCounter counter(String key) {
        return LeaseCounter.of(new ResourceLeaseCounterDocument(key, 2, AT));
    }

    @Test
    void of_splitsResourceLevelAndScope() {
        LeaseCounter c = counter("tex2pdf|project|acme/proj-1");

        assertThat(c.resource()).isEqualTo("tex2pdf");
        assertThat(c.level()).isEqualTo("project");
        assertThat(c.scope()).isEqualTo("acme/proj-1");
        assertThat(c.scopeWithinTenant()).isEqualTo("proj-1");
        assertThat(c.count()).isEqualTo(2);
        assertThat(c.updatedAt()).isEqualTo(AT);
    }

    @Test
    void of_globalLevel_hasAnEmptyScope() {
        LeaseCounter c = counter("tex2pdf|global|");

        assertThat(c.resource()).isEqualTo("tex2pdf");
        assertThat(c.level()).isEqualTo("global");
        assertThat(c.scope()).isEmpty();
    }

    @Test
    void of_resourceContainingTheSeparator_isSplitFromTheRight() {
        LeaseCounter c = counter("browser|eu|tenant|acme");

        assertThat(c.resource()).isEqualTo("browser|eu");
        assertThat(c.level()).isEqualTo("tenant");
        assertThat(c.scope()).isEqualTo("acme");
    }

    @Test
    void of_foreignKey_isKeptAsResource() {
        LeaseCounter c = counter("no-separator");

        assertThat(c.resource()).isEqualTo("no-separator");
        assertThat(c.level()).isEmpty();
    }

    @Test
    void belongsToTenant_matchesOnlyTheTenantsOwnLevels() {
        assertThat(counter("r|tenant|acme").belongsToTenant("acme")).isTrue();
        assertThat(counter("r|project|acme/p").belongsToTenant("acme")).isTrue();
        assertThat(counter("r|user|acme/alice").belongsToTenant("acme")).isTrue();

        assertThat(counter("r|global|").belongsToTenant("acme")).isFalse();
        assertThat(counter("r|tenant|acme2").belongsToTenant("acme")).isFalse();
        assertThat(counter("r|project|acme2/p").belongsToTenant("acme")).isFalse();
    }
}
