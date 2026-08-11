package de.mhus.vance.shared.schema;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.schema.SchemaMigrationService.RegisteredMigration;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integrity of {@link SchemaMigrationService#MIGRATIONS}. These are properties of
 * the source, not of a running system, so they are asserted here instead of on
 * every boot — a violation must fail the build, and it costs nothing at runtime.
 *
 * <p>Empty registry passes trivially today; the point is that every future
 * registration is checked.
 */
class SchemaMigrationRegistryTest {

    private static final List<RegisteredMigration> REGISTRY = SchemaMigrationService.MIGRATIONS;

    @Test
    void everyMigrationHasANonBlankId() {
        assertThat(REGISTRY).allSatisfy(entry ->
                assertThat(entry.id())
                        .as("id of %s", entry.type().getName())
                        .isNotBlank());
    }

    @Test
    void idsAreUnique() {
        // A duplicate id means two migrations share one marker document, so one of
        // them would never run — and the LinkedHashMap build would drop it silently.
        assertThat(REGISTRY.stream().map(RegisteredMigration::id).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void idsAreInAscendingOrder() {
        // The version model is linear: an id registered below its predecessor sits
        // below the current database version forever and is skipped.
        List<String> ids = REGISTRY.stream().map(RegisteredMigration::id).toList();
        assertThat(ids).isSorted();
    }

    @Test
    void idsFollowTheIsoDateCounterFormat() {
        // YYYY-MM-DD_NNN, counter mandatory: one id shape means one comparison
        // rule, and the fixed-width counter keeps lexicographic order equal to
        // numeric order (_002 before _010). Without this, "ascending" above would
        // not imply "chronological".
        assertThat(REGISTRY).allSatisfy(entry ->
                assertThat(entry.id())
                        .as("id of %s", entry.type().getName())
                        .matches("\\d{4}-\\d{2}-\\d{2}_\\d{3}"));
    }

    @Test
    void everyMigrationIsInstantiable() {
        // The runner creates them reflectively, only when pending — a missing or
        // non-public no-arg constructor would surface mid-run otherwise.
        assertThat(REGISTRY).allSatisfy(entry -> {
            Class<? extends SchemaMigration> type = entry.type();
            assertThat(Modifier.isPublic(type.getModifiers()))
                    .as("%s must be public", type.getName())
                    .isTrue();
            assertThat(Modifier.isPublic(type.getDeclaredConstructor().getModifiers()))
                    .as("%s needs a public no-argument constructor", type.getName())
                    .isTrue();
        });
    }
}
