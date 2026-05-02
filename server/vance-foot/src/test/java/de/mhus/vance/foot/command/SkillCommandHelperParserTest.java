package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-parser tests for the {@code [--once] [args...]} shape used by
 * {@code /skill} activation. The rest of {@link SkillCommandHelper}
 * needs a live connection — covered indirectly by integration tests.
 */
class SkillCommandHelperParserTest {

    private final SkillCommandHelper helper = new SkillCommandHelper(
            mock(ConnectionService.class),
            mock(ChatTerminal.class),
            mock(SessionService.class));

    @Test
    void noFlag_yieldsOneShotFalse_andAllTokensTrailing() {
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(
                List.of("review-mode", "do", "the", "thing"));

        assertThat(parsed.oneShot()).isFalse();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "the", "thing");
    }

    @Test
    void onceFlag_atFront_isStrippedAndOneShotTrue() {
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(
                List.of("--once", "review-mode", "do", "the", "thing"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "the", "thing");
    }

    @Test
    void onceFlag_inMiddle_isStripped_remainingTokensConsecutive() {
        // The parser tolerates --once anywhere in the token stream.
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(
                List.of("review-mode", "--once", "do", "thing"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "thing");
    }

    @Test
    void onceFlag_atEnd_isStripped() {
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(
                List.of("review-mode", "do", "thing", "--once"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "thing");
    }

    @Test
    void onlyOneFlagIsStripped_evenIfRepeated() {
        // Defensive: only the first occurrence is consumed. Spec says
        // {@code [--once]} is a single optional flag, so any further
        // {@code --once} is treated as part of the chat message.
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(
                List.of("--once", "name", "--once"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("name", "--once");
    }

    @Test
    void empty_yieldsOneShotFalse_andEmptyTokens() {
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(List.of());

        assertThat(parsed.oneShot()).isFalse();
        assertThat(parsed.trailingTokens()).isEmpty();
    }
}
