package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntegrationSurfaceTest {

    /**
     * The reason the method is part of a surface at all: in this tree add,
     * edit and remove routinely share one path. A path-only surface would hand
     * a capture integration the delete button.
     */
    @Test
    void matches_distinguishesMethodsOnTheSamePath() {
        IntegrationSurface add = IntegrationSurface.of("POST", "/addon/links/entry");

        assertThat(add.matches("POST", "/addon/links/entry")).isTrue();
        assertThat(add.matches("DELETE", "/addon/links/entry")).isFalse();
        assertThat(add.matches("PATCH", "/addon/links/entry")).isFalse();
    }

    @Test
    void matches_isCaseInsensitiveOnTheMethod() {
        assertThat(IntegrationSurface.of("post", "/x").matches("POST", "/x")).isTrue();
    }

    @Test
    void matches_tolerates_aTrailingSlash() {
        assertThat(IntegrationSurface.of("GET", "/addon/links/scan")
                .matches("GET", "/addon/links/scan/")).isTrue();
    }

    /** A surface is not a prefix — a longer path needs an explicit {@code **}. */
    @Test
    void matches_doesNotCoverDeeperPaths_withoutAWildcard() {
        IntegrationSurface scan = IntegrationSurface.of("GET", "/addon/links/scan");

        assertThat(scan.matches("GET", "/addon/links/scan/secrets")).isFalse();
    }

    @Test
    void matches_supportsAntWildcards() {
        assertThat(IntegrationSurface.of("GET", "/addon/links/**")
                .matches("GET", "/addon/links/a/b")).isTrue();
        assertThat(IntegrationSurface.of("GET", "/documents/*/content")
                .matches("GET", "/documents/abc/content")).isTrue();
        assertThat(IntegrationSurface.of("GET", "/documents/*/content")
                .matches("GET", "/documents/abc/def/content")).isFalse();
    }

    @Test
    void anyMethod_matchesEveryVerb() {
        IntegrationSurface any = IntegrationSurface.of(IntegrationSurface.ANY_METHOD, "/x");

        assertThat(any.matches("GET", "/x")).isTrue();
        assertThat(any.matches("DELETE", "/x")).isTrue();
    }

    /**
     * A relative pattern would never match anything, which is the worst kind of
     * misconfiguration here: it looks declared and denies everything.
     */
    @Test
    void rejects_aPatternThatIsNotTenantRooted() {
        assertThatThrownBy(() -> IntegrationSurface.of("GET", "addon/links/scan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    void rejects_blankMethodOrPattern() {
        assertThatThrownBy(() -> IntegrationSurface.of(" ", "/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntegrationSurface.of("GET", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
