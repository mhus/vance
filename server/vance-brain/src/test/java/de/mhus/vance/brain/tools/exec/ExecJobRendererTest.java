package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecJobRendererTest {

    private static final String SENTINEL_PREFIX = "\n…[truncated, ";

    @Test
    void render_outputBelowCap_returnedUnchanged() {
        ExecJob job = newJob();
        job.appendStdout("hello world");

        Map<String, Object> out = ExecJobRenderer.render(job, 8_000);

        assertThat(out.get("stdout")).isEqualTo("hello world\n");
        assertThat(out.get("stderr")).isEqualTo("");
        assertThat(out).doesNotContainKey("truncated");
        assertThat(out).doesNotContainKey("hint");
    }

    @Test
    void render_longStdout_keepsHeadAndTailAroundSentinel() {
        ExecJob job = newJob();
        // appendStdout adds a trailing '\n' — final char of the captured
        // stream is the newline, so the tail window ends with it.
        job.appendStdout("A".repeat(2_000) + "B".repeat(20_000) + "C".repeat(2_000));

        int cap = 8_000;
        Map<String, Object> out = ExecJobRenderer.render(job, cap);

        String rendered = (String) out.get("stdout");
        int expectedHead = cap / 5;
        int expectedTail = cap - expectedHead;
        assertThat(rendered).startsWith("A".repeat(expectedHead));
        assertThat(rendered).endsWith("C".repeat(2_000) + "\n");
        assertThat(rendered).contains(SENTINEL_PREFIX);
        // Head + sentinel + tail = cap + sentinel length
        assertThat(rendered.length()).isGreaterThan(cap);
        // Tail of cap-headBudget chars should account for the trailing '\n'
        int omittedExpected = 24_001 - cap;
        assertThat(rendered).contains(SENTINEL_PREFIX + omittedExpected + " chars omitted]");
        assertThat(out.get("truncated")).isEqualTo(true);
        assertThat(out.get("hint")).asString().contains("stdoutPath");
    }

    @Test
    void render_longStdout_omittedCountMatches() {
        ExecJob job = newJob();
        String payload = "x".repeat(50_000);
        job.appendStdout(payload);
        int totalLength = payload.length() + 1; // trailing '\n' from appendStdout

        int cap = 8_000;
        Map<String, Object> out = ExecJobRenderer.render(job, cap);

        String rendered = (String) out.get("stdout");
        int expectedOmitted = totalLength - cap; // head + tail = cap
        assertThat(rendered).contains(SENTINEL_PREFIX + expectedOmitted + " chars omitted]");
    }

    @Test
    void render_truncatedFlag_setWhenOnlyStderrOverCap() {
        ExecJob job = newJob();
        job.appendStdout("short");
        job.appendStderr("E".repeat(20_000));

        Map<String, Object> out = ExecJobRenderer.render(job, 8_000);

        assertThat(out.get("truncated")).isEqualTo(true);
        assertThat(out.get("hint")).asString().contains("stderrPath");
        assertThat(out.get("stdout")).isEqualTo("short\n");
    }

    @Test
    void render_emptyStreams_renderedAsEmptyString() {
        ExecJob job = newJob();

        Map<String, Object> out = ExecJobRenderer.render(job, 8_000);

        assertThat(out.get("stdout")).isEqualTo("");
        assertThat(out.get("stderr")).isEqualTo("");
        assertThat(out).doesNotContainKey("truncated");
    }

    private ExecJob newJob() {
        return new ExecJob(
                "id-1", "proj-1", "echo hi",
                Path.of("stdout.log"), Path.of("stderr.log"));
    }
}
