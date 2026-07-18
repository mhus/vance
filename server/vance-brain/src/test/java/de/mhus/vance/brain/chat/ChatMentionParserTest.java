package de.mhus.vance.brain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Locks down the mention-detection rules for multi-user routing.
 * Spec: {@code planning/multi-user-sessions.md} §2.2.
 *
 * <p>Simple regex parser — word boundaries, case-insensitive, no
 * code-block awareness. The cases here capture the contract; the
 * deferred-robustness items (escape sequences, code-blocks) live
 * in §8 of the plan as v2 work and are tested as expected
 * false-positives below.
 */
class ChatMentionParserTest {

    @Test
    void nullAndBlank_returnFalse() {
        assertThat(ChatMentionParser.isAddressedToAgent(null)).isFalse();
        assertThat(ChatMentionParser.isAddressedToAgent("")).isFalse();
        assertThat(ChatMentionParser.isAddressedToAgent("   ")).isFalse();
    }

    @Test
    void agentAliasesAreDetected() {
        assertThat(ChatMentionParser.isAddressedToAgent("@ai what now?")).isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("@vance please help")).isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("@arthur over here")).isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("@eddie review this")).isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("@frankie go")).isTrue();
    }

    @Test
    void caseInsensitive() {
        assertThat(ChatMentionParser.isAddressedToAgent("@AI hello")).isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("@Vance there")).isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("@ARTHUR look")).isTrue();
    }

    @Test
    void mentionAnywhereInText() {
        assertThat(ChatMentionParser.isAddressedToAgent("hey @ai can you check this"))
                .isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("question for @arthur at the end"))
                .isTrue();
    }

    @Test
    void humanBroadcastMarkersDoNotWakeAgent() {
        // @here / @all are explicit human-to-human broadcast in Slack
        // convention; they must not be treated as agent mentions.
        assertThat(ChatMentionParser.isAddressedToAgent("@here team check this"))
                .isFalse();
        assertThat(ChatMentionParser.isAddressedToAgent("@all heads up"))
                .isFalse();
    }

    @Test
    void wordBoundary_prefixedIdentifierIsNotMention() {
        // foo@bar — the @ is glued to a preceding word char. Common
        // in email addresses.
        assertThat(ChatMentionParser.isAddressedToAgent("send mail to foo@ai.com"))
                .isFalse();
    }

    @Test
    void wordBoundary_continuationIsNotMention() {
        // @ailo, @vanceish — the alias is followed by another word char.
        assertThat(ChatMentionParser.isAddressedToAgent("greetings from @ailo"))
                .isFalse();
        assertThat(ChatMentionParser.isAddressedToAgent("looking for @arthurish"))
                .isFalse();
    }

    @Test
    void noMention_returnsFalse() {
        assertThat(ChatMentionParser.isAddressedToAgent("just chatting with bob"))
                .isFalse();
        assertThat(ChatMentionParser.isAddressedToAgent("hey alice did you see this?"))
                .isFalse();
    }

    @Test
    void unknownAtNameDoesNotCount() {
        // @bob is a human handle, not an agent. Doesn't wake the agent.
        assertThat(ChatMentionParser.isAddressedToAgent("@bob check this please"))
                .isFalse();
    }

    @Test
    void multipleMentions_firstAgentWins() {
        assertThat(ChatMentionParser.isAddressedToAgent("@bob and @ai please review"))
                .isTrue();
        assertThat(ChatMentionParser.isAddressedToAgent("@here team plus @arthur"))
                .isTrue();
    }

    @Test
    void mentionInsideCodeBlock_stillCountsV1() {
        // v1: parser is NOT code-block-aware. Documented limitation.
        // If a user writes @ai inside a code block they wake the
        // agent. See plan §8 / §12 for the deferred-robustness work.
        assertThat(ChatMentionParser.isAddressedToAgent("```\nrun @ai now\n```"))
                .isTrue();
    }
}
