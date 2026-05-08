package de.mhus.vance.shared.access;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.ws.Profiles;
import org.junit.jupiter.api.Test;

/**
 * Pins the canonical profile-vs-capability table from
 * {@code engine-message-routing.md} §4.1.1. Drift between the spec
 * and the registry breaks tests, not silently the runtime.
 */
class ProfileRegistryTest {

    private final ProfileRegistry registry = new ProfileRegistry();

    @Test
    void foot_hasFullClientToolsAndCanMediate() {
        ProfileCapabilities c = registry.capabilities(Profiles.FOOT);
        assertThat(c.clientToolsEnabled()).isTrue();
        assertThat(c.canMediate()).isTrue();
        assertThat(c.forwardingTagsExpected()).isFalse();
    }

    @Test
    void web_canMediateButNoClientTools() {
        ProfileCapabilities c = registry.capabilities(Profiles.WEB);
        assertThat(c.clientToolsEnabled()).isFalse();
        assertThat(c.canMediate()).isTrue();
    }

    @Test
    void mobile_hasClientToolsButCannotMediate() {
        ProfileCapabilities c = registry.capabilities(Profiles.MOBILE);
        assertThat(c.clientToolsEnabled()).isTrue();
        assertThat(c.canMediate()).isFalse();
    }

    @Test
    void eddie_hasNoClientToolsAndExpectsForwardTags() {
        ProfileCapabilities c = registry.capabilities(Profiles.EDDIE);
        assertThat(c.clientToolsEnabled()).isFalse();
        assertThat(c.canMediate()).isFalse();
        assertThat(c.forwardingTagsExpected()).isTrue();
    }

    @Test
    void daemon_isRestrictiveDefault() {
        ProfileCapabilities c = registry.capabilities(Profiles.DAEMON);
        assertThat(c).isEqualTo(ProfileCapabilities.RESTRICTIVE);
    }

    @Test
    void unknownOrNullProfile_fallsBackToRestrictive() {
        assertThat(registry.capabilities(null)).isEqualTo(ProfileCapabilities.RESTRICTIVE);
        assertThat(registry.capabilities("")).isEqualTo(ProfileCapabilities.RESTRICTIVE);
        assertThat(registry.capabilities("kiosk")).isEqualTo(ProfileCapabilities.RESTRICTIVE);
    }
}
