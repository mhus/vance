package de.mhus.vance.foot.tools.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.tools.ClientToolService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code toolPacks:} selection from {@code .vancetope/config.yaml}:
 * an allow-list narrows, a deny-list subtracts, and an unset block lets
 * every pack through (today's behaviour for projects that don't steer).
 */
class FootToolPackSelectionTest {

    private FootConfig config;
    private FootToolPackRegistry registry;

    @BeforeEach
    void setUp() {
        config = new FootConfig();
        registry = new FootToolPackRegistry(
                mock(FootToolPackLoader.class),
                mock(EnvSecretResolver.class),
                mock(ClientToolService.class),
                mock(ProjectPackConsent.class),
                config);
    }

    @Test
    void noSelection_letsEveryPackThrough() {
        assertThat(registry.isSelected("chrome")).isTrue();
        assertThat(registry.isSelected("jira")).isTrue();
    }

    @Test
    void allowList_restrictsToItsNames() {
        config.getToolPacks().setPacks(new ArrayList<>(List.of("chrome")));

        assertThat(registry.isSelected("chrome")).isTrue();
        assertThat(registry.isSelected("jira")).isFalse();
    }

    @Test
    void emptyAllowList_isNoRestriction() {
        config.getToolPacks().setPacks(new ArrayList<>());

        assertThat(registry.isSelected("chrome")).isTrue();
    }

    @Test
    void denyList_subtracts() {
        config.getToolPacks().setDisabledPacks(new ArrayList<>(List.of("jira")));

        assertThat(registry.isSelected("chrome")).isTrue();
        assertThat(registry.isSelected("jira")).isFalse();
    }

    @Test
    void denyList_winsOverAllowList() {
        config.getToolPacks().setPacks(new ArrayList<>(List.of("chrome", "jira")));
        config.getToolPacks().setDisabledPacks(new ArrayList<>(List.of("jira")));

        assertThat(registry.isSelected("chrome")).isTrue();
        assertThat(registry.isSelected("jira")).isFalse();
    }

    @Test
    void namesMatchExactly() {
        // A pack name is the namespace prefix of its sub-tools; a prefix
        // match would silently pull in a neighbouring pack.
        config.getToolPacks().setPacks(new ArrayList<>(List.of("chrome")));

        assertThat(registry.isSelected("chrome-beta")).isFalse();
        assertThat(registry.isSelected("Chrome")).isFalse();
    }
}
