package de.mhus.vance.addon.brain.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DesktopAppConfig} parsing. */
class DesktopAppConfigTest {

    @Test
    void from_emptyBlock_yieldsDefaults() {
        DesktopAppConfig config = DesktopAppConfig.from(Map.of());
        assertThat(config.recurse()).isFalse();
        assertThat(config.root()).isNull();
        assertThat(config.include()).isEmpty();
        assertThat(config.exclude()).isEmpty();
        assertThat(config.order()).isEmpty();
    }

    @Test
    void from_lowercasesTypeLists() {
        DesktopAppConfig config = DesktopAppConfig.from(Map.of(
                "recurse", true,
                "include", List.of("Kanban", "CALENDAR"),
                "order", List.of("Kanban")));
        assertThat(config.recurse()).isTrue();
        assertThat(config.include()).containsExactly("kanban", "calendar");
        assertThat(config.order()).containsExactly("kanban");
    }

    @Test
    void from_recurseAcceptsStringBoolean() {
        assertThat(DesktopAppConfig.from(Map.of("recurse", "true")).recurse()).isTrue();
        assertThat(DesktopAppConfig.from(Map.of("recurse", "false")).recurse()).isFalse();
    }
}
