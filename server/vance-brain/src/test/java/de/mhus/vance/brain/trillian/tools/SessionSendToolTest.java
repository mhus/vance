package de.mhus.vance.brain.trillian.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@code @ai} auto-prepend of {@code session_send}: in a shared session
 * a bare message never reaches the engine at all
 * ({@code multi-user-sessions.md} §2), so the tool adds the mention — and
 * never doubles one the caller already wrote.
 */
class SessionSendToolTest {

    @Test
    void prependsTheMentionWhenMissing() {
        assertThat(SessionSendTool.addressed("please look at the report")).isEqualTo("@ai please look at the report");
    }

    @Test
    void keepsAMentionTheCallerWrote() {
        assertThat(SessionSendTool.addressed("@human I disagree")).isEqualTo("@human I disagree");
        assertThat(SessionSendTool.addressed("   @ai already there")).isEqualTo("   @ai already there");
    }
}
