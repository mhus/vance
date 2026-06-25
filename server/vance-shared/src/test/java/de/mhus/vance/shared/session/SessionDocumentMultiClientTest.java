package de.mhus.vance.shared.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Locks down the {@code allowMultipleClients} flag on
 * {@link SessionDocument} — see
 * {@code planning/multi-user-sessions.md} §2.1.
 *
 * <p>Day-1 default must be {@code false}: existing sessions and every
 * caller of {@code SessionService.create(...)} that does not explicitly
 * opt in keeps the 1:1 behaviour. Opting in is the caller's
 * responsibility, not the document's.
 */
class SessionDocumentMultiClientTest {

    @Test
    void defaultConstructor_disallowsMultipleClients() {
        SessionDocument doc = new SessionDocument();

        assertThat(doc.isAllowMultipleClients()).isFalse();
    }

    @Test
    void builder_withoutExplicitFlag_disallowsMultipleClients() {
        SessionDocument doc = SessionDocument.builder()
                .sessionId("s1")
                .tenantId("t")
                .userId("alice")
                .projectId("proj")
                .build();

        assertThat(doc.isAllowMultipleClients()).isFalse();
    }

    @Test
    void builder_optInToTrue_persistsFlag() {
        SessionDocument doc = SessionDocument.builder()
                .sessionId("s1")
                .tenantId("t")
                .userId("alice")
                .projectId("proj")
                .allowMultipleClients(true)
                .build();

        assertThat(doc.isAllowMultipleClients()).isTrue();
    }
}
