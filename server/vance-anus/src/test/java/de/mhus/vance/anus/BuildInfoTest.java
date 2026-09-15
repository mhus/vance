package de.mhus.vance.anus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The image-tag default mirrors what the publishing side pushes: releases
 * exist under {@code <version>} and {@code latest} on the registry,
 * development builds under {@code latest} only — so only a release version
 * is a resolvable pin.
 */
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

    @Test
    void releaseVersion_pinsTheExactVersion() {
        assertThat(BuildInfo.imageTagDefault("4.0.6")).isEqualTo("4.0.6");
    }

    @Test
    void snapshot_fallsBackToLatest() {
        assertThat(BuildInfo.imageTagDefault("0.5.0-SNAPSHOT")).isEqualTo("latest");
    }

    @Test
    void unfilteredClasspath_fallsBackToLatest() {
        // Raw IDE sources carry no Maven-injected version ("dev") — there is
        // no such tag on the registry.
        assertThat(BuildInfo.imageTagDefault("dev")).isEqualTo("latest");
    }

    @Test
    void blankVersion_fallsBackToLatest() {
        assertThat(BuildInfo.imageTagDefault("")).isEqualTo("latest");
    }

    @Test
    void imageTagDefault_isAlwaysTheVersionOrLatest() {
        // Against the real classpath stamp (filtered in a Maven build, "dev"
        // in a raw IDE run) the default is either the exact pin or the
        // rolling tag — never anything unresolvable.
        assertThat(BuildInfo.imageTagDefault()).isIn(BuildInfo.version(), "latest");
    }
}
