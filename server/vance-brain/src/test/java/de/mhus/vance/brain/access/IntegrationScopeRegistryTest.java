package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntegrationScopeRegistryTest {

    private record Profile(String id, List<IntegrationSurface> surfaces)
            implements IntegrationScopeProfile {
        @Override
        public String label() {
            return id;
        }
    }

    private static Profile profile(String id) {
        return new Profile(id, List.of(IntegrationSurface.of("GET", "/x")));
    }

    @Test
    void findsProfilesById() {
        IntegrationScopeRegistry registry =
                new IntegrationScopeRegistry(List.of(profile("a"), profile("b")));

        assertThat(registry.find("a")).isPresent();
        assertThat(registry.find("nope")).isEmpty();
        assertThat(registry.all()).hasSize(2);
    }

    /**
     * Two profiles under one name would make the {@code scp} claim resolve by
     * bean ordering — the same token granting different surfaces depending on
     * how Spring felt that morning. An ambiguity in an authorization input is a
     * startup failure.
     */
    @Test
    void duplicateId_failsTheBoot() {
        assertThatThrownBy(() ->
                new IntegrationScopeRegistry(List.of(profile("links-capture"), profile("links-capture"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate IntegrationScopeProfile id 'links-capture'");
    }

    /** A profile with no surfaces can only mint tokens rejected on every call. */
    @Test
    void profileWithoutSurfaces_failsTheBoot() {
        assertThatThrownBy(() ->
                new IntegrationScopeRegistry(List.of(new Profile("empty", List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no surfaces");
    }

    /** No addon wanting integration tokens is a normal state, not an error. */
    @Test
    void emptyRegistry_isFine() {
        assertThat(new IntegrationScopeRegistry(List.of()).all()).isEmpty();
    }
}
