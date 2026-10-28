package de.mhus.vance.brain.tools.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.exec.WhisperEngine;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The runner's transcript answer becomes the pipeline's own result type. */
class RunnerTranscriptTest {

    private final RunnerWork runner = mock(RunnerWork.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext("acme", "p1", "s", "proc", "alice");

    @Test
    void theRunnersSegmentsBecomeAWhisperResult() {
        when(runner.call(any(), eq("video.transcript"), any(), any(), any(), any()))
                .thenReturn(Map.of(
                        "language",
                        "de",
                        "languageProbability",
                        0.97,
                        "durationSec",
                        12.5,
                        "elapsedSec",
                        3.25,
                        "modelName",
                        "small",
                        "segments",
                        List.of(
                                Map.of("start", 0.0, "end", 4.0, "text", "Hallo Welt"),
                                Map.of("start", 4.0, "end", 12.5, "text", "zweiter Satz"))));

        WhisperEngine.Result result =
                RunnerTranscript.fetch(runner, ctx, "dQw4w9WgXcQ", "small", "de", Duration.ofMinutes(5));

        assertThat(result.language()).isEqualTo("de");
        assertThat(result.languageProbability()).isEqualTo(0.97);
        assertThat(result.durationSec()).isEqualTo(12.5);
        assertThat(result.elapsedSec()).isEqualTo(3.25);
        assertThat(result.modelName()).isEqualTo("small");
        assertThat(result.segments())
                .containsExactly(
                        new WhisperEngine.Fragment(0.0, 4.0, "Hallo Welt"),
                        new WhisperEngine.Fragment(4.0, 12.5, "zweiter Satz"));
    }

    @Test
    void theRequestNamesTheVideoTheModelAndOnlyAGivenLanguage_andWaitsAsLongAsAskedFor() {
        when(runner.call(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("segments", List.of()));

        RunnerTranscript.fetch(runner, ctx, "dQw4w9WgXcQ", "small", null, Duration.ofMinutes(7));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(runner).call(eq(ctx), eq("video.transcript"), args.capture(), any(), any(), timeout.capture());
        assertThat(args.getValue())
                .containsEntry("videoId", "dQw4w9WgXcQ")
                .containsEntry("model", "small")
                .doesNotContainKey("language");
        assertThat(timeout.getValue()).isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void aMissingFieldIsEmptyNotACrash() {
        when(runner.call(any(), any(), any(), any(), any(), any())).thenReturn(Map.of());

        WhisperEngine.Result result =
                RunnerTranscript.fetch(runner, ctx, "dQw4w9WgXcQ", "small", null, Duration.ofMinutes(1));

        assertThat(result.segments()).isEmpty();
        assertThat(result.durationSec()).isZero();
    }

    @Test
    void aRunnerFailureStaysAToolException() {
        when(runner.call(any(), any(), any(), any(), any(), any())).thenThrow(new ToolException("yt-dlp failed"));

        assertThatThrownBy(
                        () -> RunnerTranscript.fetch(runner, ctx, "dQw4w9WgXcQ", "small", null, Duration.ofMinutes(1)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("yt-dlp failed");
    }
}
