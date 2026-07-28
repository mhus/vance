package de.mhus.vance.anus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildInfoTest {

    @Test
    void version_readFromFilteredApplicationYaml_notThePlaceholder() {
        // application.yml is resource-filtered before tests run, so the Maven
        // reactor version lands here — never the raw @project.version@ token.
        assertThat(BuildInfo.version()).isNotBlank().doesNotContain("@");
    }

    @Test
    void line_startsWithProductName() {
        assertThat(BuildInfo.line()).startsWith("vance-anus ");
    }
}
