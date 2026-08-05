package de.mhus.vance.foot.command;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import de.mhus.vance.foot.tools.exec.ClientExecStat;
import de.mhus.vance.foot.tools.exec.ClientExecStatus;
import de.mhus.vance.foot.tools.exec.ClientExecutorService;
import de.mhus.vance.foot.ui.InterfaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /ui-exec} — fullscreen Lanterna browser for the shell jobs
 * running on <b>this machine</b>. Source is the local
 * {@link ClientExecutorService} index, so the list only ever shows
 * client-side jobs (whatever the brain started via
 * {@code client_exec_run} plus anything the local tooling submitted) —
 * brain-side {@code work_exec_*} jobs are not visible here.
 *
 * <p>Layout mirrors {@link UiInboxCommand}: a master list with a
 * RUNNING/ALL filter, a detail window per job, and read-only output
 * windows. Every exec job streams into
 * {@code data/client-exec/<jobId>/{stdout,stderr}.log}, so the output
 * views simply tail those files — cheap, and identical to what the LLM
 * sees through {@code client_exec_tail}.
 *
 * <p>The job index lives in memory only: after a foot restart the log
 * files are still on disk but no longer listed here (a re-attach to a
 * process that's gone would be a lie).
 */
@Component
public class UiExecCommand implements SlashCommand {

    /**
     * Upper bound for an output window. Deliberately far above the
     * {@code client_exec_tail} tool cap — a human scrolling a log wants
     * more context than an LLM budget allows — but still bounded so a
     * runaway build log can't blow up the TUI.
     */
    private static final int MAX_OUTPUT_LINES = 5_000;

    private final ClientExecutorService executor;
    private final InterfaceService ui;

    public UiExecCommand(ClientExecutorService executor, InterfaceService ui) {
        this.executor = executor;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "ui-exec";
    }

    @Override
    public String description() {
        return "Browse the local shell jobs (status, details, stdout/stderr output, kill).";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        ui.runFullscreen(session -> {
            View view = new View(session.gui());
            view.refresh();
            session.gui().addWindowAndWait(view.window);
        });
    }

    /** Master view: header + job list + action buttons. */
    private final class View {

        private final WindowBasedTextGUI gui;
        private final BasicWindow window;
        private final Label header = new Label("");
        private final ActionListBox listBox = new ActionListBox();
        private final Button filterButton;
        private boolean runningOnly = false;
        private List<ClientExecStat> jobs = List.of();

        View(WindowBasedTextGUI gui) {
            this.gui = gui;
            this.window = new BasicWindow("Local exec jobs");
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            this.filterButton = new Button(filterButtonLabel(), this::toggleFilter);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(header);
            // Leading space compensates for the list border's left line so
            // the column captions sit above their values.
            root.addComponent(new Label(" " + String.format(
                    "%-8s  %-9s  %4s  %8s  %8s  %s",
                    "id", "status", "exit", "runtime", "idle", "command")));

            Border listBorder = listBox.withBorder(Borders.singleLine("Jobs"));
            listBorder.setLayoutData(LinearLayout.createLayoutData(
                    LinearLayout.Alignment.Fill,
                    LinearLayout.GrowPolicy.CanGrow));
            root.addComponent(listBorder);

            Panel actions = new Panel();
            actions.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            actions.addComponent(new Button("Refresh", this::refresh));
            actions.addComponent(filterButton);
            actions.addComponent(new Button("Details", this::openSelected));
            actions.addComponent(new Button("Stdout",
                    () -> withSelected(job -> showOutput(job, ClientExecutorService.Stream.STDOUT))));
            actions.addComponent(new Button("Stderr",
                    () -> withSelected(job -> showOutput(job, ClientExecutorService.Stream.STDERR))));
            actions.addComponent(new Button("Kill", () -> withSelected(this::kill)));
            actions.addComponent(new Button("Close", window::close));
            root.addComponent(actions);

            window.setComponent(root);
        }

        void refresh() {
            List<ClientExecStat> all = executor.statAll();
            jobs = runningOnly
                    ? all.stream().filter(s -> s.status() == ClientExecStatus.RUNNING).toList()
                    : all;
            long running = all.stream()
                    .filter(s -> s.status() == ClientExecStatus.RUNNING)
                    .count();
            header.setText(all.size() + " job(s) tracked, " + running + " running"
                    + (runningOnly ? "   (showing running only)" : "")
                    + "   Enter = details");
            listBox.clearItems();
            if (jobs.isEmpty()) {
                listBox.addItem(runningOnly
                        ? "(nothing running)"
                        : "(no local exec jobs — they appear once a command runs on this machine)",
                        () -> {});
                return;
            }
            for (ClientExecStat job : jobs) {
                listBox.addItem(formatRow(job, Instant.now()), () -> openDetail(job.id()));
            }
        }

        private void toggleFilter() {
            runningOnly = !runningOnly;
            filterButton.setLabel(filterButtonLabel());
            refresh();
        }

        private String filterButtonLabel() {
            return runningOnly ? "Filter: RUNNING" : "Filter: ALL";
        }

        private @Nullable ClientExecStat selected() {
            int idx = listBox.getSelectedIndex();
            if (idx < 0 || idx >= jobs.size()) return null;
            return jobs.get(idx);
        }

        private void withSelected(Consumer<ClientExecStat> action) {
            ClientExecStat job = selected();
            if (job == null) return;
            action.accept(job);
        }

        private void openSelected() {
            withSelected(job -> openDetail(job.id()));
        }

        /**
         * Re-reads the job before opening its detail window — the row was
         * rendered at refresh time and a RUNNING job may have finished
         * since.
         */
        private void openDetail(String jobId) {
            ClientExecStat fresh = executor.stat(jobId).orElse(null);
            if (fresh == null) {
                error("Job '" + jobId + "' is no longer tracked (evicted from the index).");
                refresh();
                return;
            }
            Detail detail = new Detail(gui, fresh);
            gui.addWindowAndWait(detail.window);
            refresh();
        }

        private void kill(ClientExecStat job) {
            if (job.status() != ClientExecStatus.RUNNING) {
                error("Job " + job.id() + " already finished (" + job.status() + ").");
                return;
            }
            MessageDialogButton choice = new MessageDialogBuilder()
                    .setTitle("Confirm kill")
                    .setText("Kill job " + job.id() + "?\n\n" + job.command()
                            + "\n\nSIGTERM to the whole process tree, SIGKILL after 10s.")
                    .addButton(MessageDialogButton.Yes)
                    .addButton(MessageDialogButton.No)
                    .build()
                    .showDialog(gui);
            if (choice != MessageDialogButton.Yes) return;
            boolean killed = executor.kill(job.id());
            if (!killed) {
                error("Kill did not apply — the job finished in the meantime.");
            }
            refresh();
        }

        private void showOutput(ClientExecStat job, ClientExecutorService.Stream stream) {
            UiExecCommand.this.showOutput(gui, job, stream);
        }

        private void error(String message) {
            MessageDialog.showMessageDialog(gui, "Exec jobs", message, MessageDialogButton.OK);
        }
    }

    /**
     * Per-job detail window: the full command plus every field the
     * executor tracks, and the same output/kill actions as the master
     * view. Reload re-reads the snapshot so a running job's counters
     * advance.
     */
    private final class Detail {

        private final WindowBasedTextGUI gui;
        private final BasicWindow window;
        private final Label info = new Label("");
        private ClientExecStat job;

        Detail(WindowBasedTextGUI gui, ClientExecStat job) {
            this.gui = gui;
            this.job = job;
            this.window = new BasicWindow("Exec job " + job.id());
            window.setHints(Set.of(Window.Hint.CENTERED, Window.Hint.FIT_TERMINAL_WINDOW));
            window.setCloseWindowWithEscape(true);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));

            TextBox command = new TextBox(new TerminalSize(80, 3),
                    job.command(), TextBox.Style.MULTI_LINE);
            command.setReadOnly(true);
            root.addComponent(command.withBorder(Borders.singleLine("Command")));

            root.addComponent(info.withBorder(Borders.singleLine("Details")));

            Panel buttons = new Panel();
            buttons.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            buttons.addComponent(new Button("Stdout",
                    () -> UiExecCommand.this.showOutput(gui, this.job, ClientExecutorService.Stream.STDOUT)));
            buttons.addComponent(new Button("Stderr",
                    () -> UiExecCommand.this.showOutput(gui, this.job, ClientExecutorService.Stream.STDERR)));
            buttons.addComponent(new Button("Kill", this::kill));
            buttons.addComponent(new Button("Reload", this::reload));
            buttons.addComponent(new Button("Close", window::close));
            root.addComponent(buttons);

            window.setComponent(root);
            renderInfo();
        }

        private void renderInfo() {
            Instant now = Instant.now();
            StringBuilder sb = new StringBuilder();
            sb.append("Status:     ").append(job.status())
              .append(job.timedOut() ? "  (killed by timeout)" : "").append('\n');
            sb.append("Exit code:  ")
              .append(job.exitCode() == null ? "—" : job.exitCode().toString()).append('\n');
            sb.append("Runtime:    ").append(humanDuration(job.durationMs())).append('\n');
            sb.append("Started:    ").append(job.startedAt()).append('\n');
            sb.append("Last out:   ").append(job.lastOutputAt())
              .append("  (").append(humanDuration(
                      Duration.between(job.lastOutputAt(), now).toMillis())).append(" ago)\n");
            sb.append("Finished:   ")
              .append(job.finishedAt() == null ? "— (still running)" : job.finishedAt().toString())
              .append('\n');
            sb.append("Session:    ").append(Objects.toString(job.sessionId(), "— (local)")).append('\n');
            sb.append("Project:    ").append(Objects.toString(job.projectId(), "— (local)")).append('\n');
            sb.append("Stdout:     ").append(humanSize(job.stdoutBytes()))
              .append("  ").append(job.stdoutPath()).append('\n');
            sb.append("Stderr:     ").append(humanSize(job.stderrBytes()))
              .append("  ").append(job.stderrPath());
            info.setText(sb.toString());
        }

        private void reload() {
            ClientExecStat fresh = executor.stat(job.id()).orElse(null);
            if (fresh == null) {
                MessageDialog.showMessageDialog(gui, "Exec job",
                        "Job " + job.id() + " is no longer tracked.", MessageDialogButton.OK);
                window.close();
                return;
            }
            job = fresh;
            renderInfo();
        }

        private void kill() {
            if (job.status() != ClientExecStatus.RUNNING) {
                MessageDialog.showMessageDialog(gui, "Exec job",
                        "Already finished (" + job.status() + ").", MessageDialogButton.OK);
                return;
            }
            MessageDialogButton choice = new MessageDialogBuilder()
                    .setTitle("Confirm kill")
                    .setText("Kill job " + job.id() + "?")
                    .addButton(MessageDialogButton.Yes)
                    .addButton(MessageDialogButton.No)
                    .build()
                    .showDialog(gui);
            if (choice != MessageDialogButton.Yes) return;
            executor.kill(job.id());
            reload();
        }
    }

    /**
     * Read-only tail of one stream in its own fullscreen window. Reload
     * re-tails the file, which is what makes this usable on a job that's
     * still producing output.
     */
    private void showOutput(WindowBasedTextGUI gui,
                            ClientExecStat job,
                            ClientExecutorService.Stream stream) {
        String streamName = stream == ClientExecutorService.Stream.STDERR ? "stderr" : "stdout";
        String path = stream == ClientExecutorService.Stream.STDERR
                ? job.stderrPath() : job.stdoutPath();

        BasicWindow window = new BasicWindow(job.id() + " — " + streamName);
        window.setHints(Set.of(Window.Hint.FULL_SCREEN));
        window.setCloseWindowWithEscape(true);

        Label head = new Label("");
        TextBox box = new TextBox(new TerminalSize(80, 20), "", TextBox.Style.MULTI_LINE);
        box.setReadOnly(true);
        box.setLayoutData(LinearLayout.createLayoutData(
                LinearLayout.Alignment.Fill,
                LinearLayout.GrowPolicy.CanGrow));

        Runnable reload = () -> {
            List<String> lines;
            try {
                lines = executor.tail(job.id(), MAX_OUTPUT_LINES, stream);
            } catch (RuntimeException e) {
                box.setText("");
                head.setText("Tail failed: " + e.getMessage());
                return;
            }
            box.setText(lines.isEmpty() ? "(no output)" : String.join("\n", lines));
            head.setText(path + "   " + lines.size() + " line(s)"
                    + (lines.size() >= MAX_OUTPUT_LINES
                            ? " — capped at the last " + MAX_OUTPUT_LINES : ""));
        };
        reload.run();

        Panel root = new Panel();
        root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
        root.addComponent(head);
        root.addComponent(box.withBorder(Borders.singleLine(streamName)));
        Panel buttons = new Panel();
        buttons.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
        buttons.addComponent(new Button("Reload", reload));
        buttons.addComponent(new Button("Close", window::close));
        root.addComponent(buttons);

        window.setComponent(root);
        gui.addWindowAndWait(window);
    }

    // ──────────────────── Formatting ────────────────────

    /**
     * One master-list row. {@code idle} is the time since the last
     * output line — the field that tells a stuck job from a slow one.
     */
    static String formatRow(ClientExecStat job, Instant now) {
        return String.format("%-8s  %-9s  %4s  %8s  %8s  %s",
                job.id(),
                job.status(),
                job.exitCode() == null ? "—" : job.exitCode().toString(),
                humanDuration(job.durationMs()),
                humanDuration(Duration.between(job.lastOutputAt(), now).toMillis()),
                truncate(job.command().replace('\n', ' '), 70));
    }

    static String humanDuration(long millis) {
        if (millis < 0) return "0s";
        if (millis < 10_000) return String.format("%.1fs", millis / 1000.0);
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return String.format("%dm%02ds", minutes, seconds % 60);
        return String.format("%dh%02dm", minutes / 60, minutes % 60);
    }

    static String humanSize(long n) {
        if (n < 1024) return n + "B";
        if (n < 1024 * 1024) return String.format("%.1fK", n / 1024.0);
        return String.format("%.1fM", n / (1024.0 * 1024));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
