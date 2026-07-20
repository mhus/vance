package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.EXEC_KILL_GRACE_SECONDS;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.NO_DEADLINE_WAIT_MS;

import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.exec.SubmitOptions;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * WORK {@link ComposeExec}: submits the command to {@link ExecManager} in the
 * server-side workspace ({@code dirName} = cwd), blocking slightly past the
 * hard-kill deadline so the terminal (possibly killed) state is observed rather
 * than a still-RUNNING snapshot. Reports the tracked job id to the async run's
 * live tail via {@code progress}.
 */
final class WorkspaceComposeExec implements ComposeExec {

    private final ExecManager execManager;
    private final String tenantId;
    private final String projectId;
    private final String dirName;
    private final @Nullable String processId;
    private final @Nullable ComposeProgress progress;

    WorkspaceComposeExec(ExecManager execManager, String tenantId, String projectId,
                         String dirName, @Nullable String processId, @Nullable ComposeProgress progress) {
        this.execManager = execManager;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.dirName = dirName;
        this.processId = processId;
        this.progress = progress;
    }

    @Override
    public Result run(String command, int deadlineSeconds) {
        SubmitOptions options;
        long waitMs;
        if (deadlineSeconds <= 0) {
            options = SubmitOptions.defaults();
            waitMs = NO_DEADLINE_WAIT_MS;
        } else {
            options = SubmitOptions.withDeadline(Instant.now().plusSeconds(deadlineSeconds));
            waitMs = (deadlineSeconds + EXEC_KILL_GRACE_SECONDS) * 1000L;
        }
        Consumer<String> onJobId = progress == null ? null : progress::execJob;

        Map<String, Object> rendered = execManager.submitTrackedAndRender(
                tenantId, projectId, null, processId, dirName, command, waitMs, options, onJobId);

        return new Result(
                String.valueOf(rendered.get("status")),
                exitCodeOf(rendered.get("exitCode")),
                text(rendered.get("stdout")),
                text(rendered.get("stderr")));
    }

    private static int exitCodeOf(@Nullable Object raw) {
        return raw instanceof Number n ? n.intValue() : -1;
    }

    private static String text(@Nullable Object raw) {
        return raw == null ? "" : raw.toString();
    }
}
