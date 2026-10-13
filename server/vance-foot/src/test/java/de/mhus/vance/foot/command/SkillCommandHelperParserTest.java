package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillActivationSource;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillSummaryDto;
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
            mock(SessionService.class),
            mock(de.mhus.vance.foot.connection.BrainRestClientService.class));

    @Test
    void noFlag_yieldsOneShotFalse_andAllTokensTrailing() {
        SkillCommandHelper.ParsedActivateArgs parsed =
                helper.parseActivateArgs(List.of("review-mode", "do", "the", "thing"));

        assertThat(parsed.oneShot()).isFalse();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "the", "thing");
    }

    @Test
    void onceFlag_atFront_isStrippedAndOneShotTrue() {
        SkillCommandHelper.ParsedActivateArgs parsed =
                helper.parseActivateArgs(List.of("--once", "review-mode", "do", "the", "thing"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "the", "thing");
    }

    @Test
    void onceFlag_inMiddle_isStripped_remainingTokensConsecutive() {
        // The parser tolerates --once anywhere in the token stream.
        SkillCommandHelper.ParsedActivateArgs parsed =
                helper.parseActivateArgs(List.of("review-mode", "--once", "do", "thing"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "thing");
    }

    @Test
    void onceFlag_atEnd_isStripped() {
        SkillCommandHelper.ParsedActivateArgs parsed =
                helper.parseActivateArgs(List.of("review-mode", "do", "thing", "--once"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("review-mode", "do", "thing");
    }

    @Test
    void onlyOneFlagIsStripped_evenIfRepeated() {
        // Defensive: only the first occurrence is consumed. Spec says
        // {@code [--once]} is a single optional flag, so any further
        // {@code --once} is treated as part of the chat message.
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(List.of("--once", "name", "--once"));

        assertThat(parsed.oneShot()).isTrue();
        assertThat(parsed.trailingTokens()).containsExactly("name", "--once");
    }

    @Test
    void empty_yieldsOneShotFalse_andEmptyTokens() {
        SkillCommandHelper.ParsedActivateArgs parsed = helper.parseActivateArgs(List.of());

        assertThat(parsed.oneShot()).isFalse();
        assertThat(parsed.trailingTokens()).isEmpty();
    }

    @Test
    void modeLabel_showsTheEffectiveMode_andTheOverrideTier() {
        SkillSummaryDto derived = SkillSummaryDto.builder()
                .name("a")
                .activation(SkillActivation.TRIGGER)
                .activationSource(SkillActivationSource.DERIVED)
                .build();
        SkillSummaryDto overridden = SkillSummaryDto.builder()
                .name("b")
                .activation(SkillActivation.MANUAL)
                .activationSource(SkillActivationSource.OVERRIDE)
                .activationOverrideScope(SkillScope.USER)
                .build();

        assertThat(SkillCommandHelper.modeLabel(derived)).isEqualTo("mode:trigger");
        assertThat(SkillCommandHelper.modeLabel(overridden)).isEqualTo("mode:manual(user)");
    }

    @Test
    void modeLabel_marksSkillsTheRecipeHides() {
        SkillSummaryDto irrelevant = SkillSummaryDto.builder()
                .name("code-review")
                .activation(SkillActivation.AGENT)
                .activationSource(SkillActivationSource.FRONTMATTER)
                .relevant(false)
                .build();
        SkillSummaryDto declaredHidden = SkillSummaryDto.builder()
                .name("experimental")
                .activation(SkillActivation.HIDDEN)
                .activationSource(SkillActivationSource.FRONTMATTER)
                .relevant(false)
                .build();

        assertThat(SkillCommandHelper.modeLabel(irrelevant)).isEqualTo("mode:agent → hidden(recipe)");
        assertThat(SkillCommandHelper.modeLabel(declaredHidden)).isEqualTo("mode:hidden");
    }

    @Test
    void stickyFlag_isStrippedAnywhere_andReported() {
        SkillCommandHelper.ParsedActivateArgs parsed =
                helper.parseActivateArgs(List.of("write", "--sticky", "the", "report"));

        assertThat(parsed.sticky()).isTrue();
        assertThat(parsed.oneShot()).isFalse();
        assertThat(parsed.trailingTokens()).containsExactly("write", "the", "report");
    }

    @Test
    void activationMessage_turnSkill_saysOneTurnUnlessSticky() {
        de.mhus.vance.api.skills.ProcessSkillResponse turn = de.mhus.vance.api.skills.ProcessSkillResponse.builder()
                .newlyActivated(true)
                .lifecycle("turn")
                .build();

        assertThat(SkillCommandHelper.activationMessage("report-writing", turn, false, false, false))
                .contains("(once)")
                .contains("next turn only");
        assertThat(SkillCommandHelper.activationMessage("report-writing", turn, false, true, false))
                .doesNotContain("(once)")
                .contains("in effect from the next turn");
    }
}
