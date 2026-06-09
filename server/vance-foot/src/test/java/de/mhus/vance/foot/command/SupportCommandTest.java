package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.fook.FookSubmissionRequestDto;
import de.mhus.vance.api.fook.FookSubmissionResponseDto;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Behavioural tests for {@link SupportCommand}. Inline mode is
 * exercised directly; the form-mode path is skipped because the
 * Lanterna excursion requires a real TTY and tests run headless.
 */
class SupportCommandTest {

    private InterfaceService ui;
    private ChatTerminal terminal;
    private BrainRestClientService restClient;
    private FootConfig config;
    private SupportCommand command;

    @BeforeEach
    void setUp() throws Exception {
        ui = mock(InterfaceService.class);
        terminal = mock(ChatTerminal.class);
        restClient = mock(BrainRestClientService.class);
        config = new FootConfig();
        config.getAuth().setTenant("acme");

        when(restClient.post(any(), any(), eq(FookSubmissionResponseDto.class)))
                .thenReturn(FookSubmissionResponseDto.builder()
                        .submissionId("sub-xyz")
                        .status("queued")
                        .build());
        command = new SupportCommand(ui, terminal, restClient, config);
    }

    // ─── inline submission ──────────────────────────────────────────

    @Test
    void inline_text_joins_args_with_spaces_and_submits() throws Exception {
        command.execute(List.of("Brain", "crashes", "on", "boot."));

        ArgumentCaptor<FookSubmissionRequestDto> bodyCap =
                ArgumentCaptor.forClass(FookSubmissionRequestDto.class);
        verify(restClient).post(
                eq("/brain/acme/fook/submit"),
                bodyCap.capture(),
                eq(FookSubmissionResponseDto.class));
        assertThat(bodyCap.getValue().getText())
                .isEqualTo("Brain crashes on boot.");
        verifyNoInteractions(ui);
    }

    @Test
    void inline_submission_prints_submission_id_at_info() throws Exception {
        command.execute(List.of("Brain", "crashed."));
        // submissionId lands in the varargs position; the format string
        // carries the surrounding "submitted" copy.
        verify(terminal).println(eq(Verbosity.INFO),
                contains("submitted"), eq("sub-xyz"));
    }

    @Test
    void rest_failure_surfaces_as_terminal_error_line() throws Exception {
        when(restClient.post(any(), any(), eq(FookSubmissionResponseDto.class)))
                .thenThrow(new IllegalStateException(
                        "REST POST /brain/acme/fook/submit failed: HTTP 401"));

        command.execute(List.of("Anything"));

        verify(terminal).println(
                eq(Verbosity.ERROR),
                contains("Failed to submit"),
                any());
    }

    // ─── form mode (args empty) ─────────────────────────────────────

    @Test
    void no_args_opens_lanterna_form() throws Exception {
        // We don't actually run the Lanterna excursion; just verify
        // the InterfaceService is invoked. The form returning null
        // (no text captured) prints the "cancelled" line.
        command.execute(List.of());
        verify(ui).runFullscreen(any());
        verify(restClient, never()).post(any(), any(), any());
        verify(terminal).println(eq(Verbosity.INFO), contains("cancelled"));
    }

    @Test
    void blank_args_treated_as_no_text_and_opens_form() throws Exception {
        // /support followed by only whitespace tokens — collapse to empty.
        command.execute(List.of("   ", "   "));
        verify(ui).runFullscreen(any());
    }

    // ─── metadata ───────────────────────────────────────────────────

    @Test
    void name_and_description_are_set() {
        assertThat(command.name()).isEqualTo("support");
        assertThat(command.description()).contains("/support");
    }
}
