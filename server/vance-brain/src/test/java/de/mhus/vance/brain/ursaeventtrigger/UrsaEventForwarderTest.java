package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The forwarding marker is a claim by the caller, so it has to be proven.
 * {@code /brain/**} never passes {@code InternalAccessFilter}, which means
 * this is the only place the internal token is checked on the event route.
 */
class UrsaEventForwarderTest {

    private static final String TOKEN = "s3cret-internal";

    @Test
    void trustedHop_requiresBothMarkerAndToken() {
        UrsaEventForwarder forwarder = new UrsaEventForwarder(TOKEN);

        assertThat(forwarder.isTrustedHop("1", TOKEN)).isTrue();
    }

    @Test
    void trustedHop_rejectsMarkerWithoutToken() {
        // The spoof case: an external webhook caller sets the header to skip
        // owner resolution and have the trigger run on whichever pod answered.
        UrsaEventForwarder forwarder = new UrsaEventForwarder(TOKEN);

        assertThat(forwarder.isTrustedHop("1", null)).isFalse();
        assertThat(forwarder.isTrustedHop("1", "")).isFalse();
    }

    @Test
    void trustedHop_rejectsWrongToken() {
        UrsaEventForwarder forwarder = new UrsaEventForwarder(TOKEN);

        assertThat(forwarder.isTrustedHop("1", "guessed")).isFalse();
    }

    @Test
    void trustedHop_rejectsTokenWithoutMarker() {
        UrsaEventForwarder forwarder = new UrsaEventForwarder(TOKEN);

        assertThat(forwarder.isTrustedHop(null, TOKEN)).isFalse();
    }

    @Test
    void trustedHop_trustsNothingWhenNoInternalTokenIsConfigured() {
        // Single-pod deployments never forward, so "trust nothing" costs
        // nothing there — and an empty configured secret must not become a
        // secret that everybody knows.
        UrsaEventForwarder forwarder = new UrsaEventForwarder("");

        assertThat(forwarder.isTrustedHop("1", "")).isFalse();
        assertThat(forwarder.isTrustedHop("1", "anything")).isFalse();
    }
}
