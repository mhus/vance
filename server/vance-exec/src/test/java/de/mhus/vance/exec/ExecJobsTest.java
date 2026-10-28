package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The job engine on its own: real {@code /bin/sh} processes, recording listener. */
class ExecJobsTest {

    private static final String SCOPE = "acme/proj";

    @TempDir
    Path tmp;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private ExecJobs jobs;

    @BeforeEach
    void setUp() {
        jobs = engine(50, 10);
    }

    @AfterEach
    void tearDown() {
        jobs.close();
    }

    private ExecJobs engine(long killGraceMs, int maxJobsPerScope) {
        return new ExecJobs(
                new ExecJobs.Config(tmp.resolve("jobs"), killGraceMs, maxJobsPerScope, Duration.ofMinutes(2)),
                new ExecJobs.Listener() {
                    @Override
                    public void stateChanged(ExecJob job) {
                        events.add("state:" + job.id() + ":" + job.status());
                    }

                    @Override
                    public void finished(ExecJob job) {
                        events.add("finished:" + job.id());
                    }

                    @Override
                    public void evicted(ExecJob job) {
                        events.add("evicted:" + job.id());
                    }
                });
    }

    private ExecJob run(String command, Instant deadline) {
        return jobs.submit(new ExecJobs.Submission(
                SCOPE,
                "proj",
                null,
                command,
                ExecIsolation.shellArgv(command),
                tmp,
                Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin")),
                Map.of(),
                deadline));
    }

    @Test
    void completedJob_capturesOutputAndReportsStateThenFinished() throws Exception {
        ExecJob job = jobs.waitFor(run("echo hello; echo oops >&2", null), 10_000);
        waitUntil(() -> events.contains("finished:" + job.id()));

        assertThat(job.status()).isEqualTo(ExecJob.Status.COMPLETED);
        assertThat(job.exitCode()).isZero();
        assertThat(job.readStdout()).contains("hello");
        assertThat(job.readStderr()).contains("oops");
        assertThat(Files.readString(job.stdoutFile())).contains("hello");
        assertThat(events).containsSubsequence("state:" + job.id() + ":COMPLETED", "finished:" + job.id());
    }

    @Test
    void nonZeroExit_isFailed() {
        ExecJob job = jobs.waitFor(run("exit 3", null), 10_000);

        assertThat(job.status()).isEqualTo(ExecJob.Status.FAILED);
        assertThat(job.exitCode()).isEqualTo(3);
    }

    @Test
    void unstartableProgram_isFailedWithTheErrorInStderr() {
        ExecJob job = jobs.submit(new ExecJobs.Submission(
                SCOPE, "proj", null, "x", List.of("/nonexistent/binary"), tmp, Map.of(), Map.of(), null));
        jobs.waitFor(job, 10_000);

        assertThat(job.status()).isEqualTo(ExecJob.Status.FAILED);
        assertThat(job.readStderr()).startsWith("ERROR: IOException");
    }

    @Test
    void kill_stopsTheJobAndReportsStateOnly_thenTheWorkerFinishes() throws Exception {
        ExecJob job = run("sleep 30", null);
        waitUntil(() -> job.process() != null);

        assertThat(jobs.kill(SCOPE, job.id())).isTrue();
        waitUntil(() -> events.contains("finished:" + job.id()));

        assertThat(job.status()).isEqualTo(ExecJob.Status.KILLED);
        assertThat(jobs.kill(SCOPE, job.id())).as("already terminal").isFalse();
    }

    @Test
    void deadline_killsTheJobViaTheWatchdog() throws Exception {
        ExecJob job = run("sleep 30", Instant.now().plusMillis(300));

        jobs.waitFor(job, 10_000);

        assertThat(job.killedByWatchdog()).isTrue();
        assertThat(job.status()).isEqualTo(ExecJob.Status.KILLED);
    }

    @Test
    void extendDeadline_movesTheKillOut_andRefusesNonPositive() throws Exception {
        ExecJob job = run("sleep 30", Instant.now().plusMillis(300));
        waitUntil(() -> job.process() != null);

        assertThat(jobs.extendDeadline(SCOPE, job.id(), Duration.ofSeconds(30))).isTrue();
        Thread.sleep(700);

        assertThat(job.isTerminal())
                .as("deadline was extended past the original")
                .isFalse();
        assertThatThrownBy(() -> jobs.extendDeadline(SCOPE, job.id(), Duration.ZERO))
                .isInstanceOf(ExecException.class);
        assertThat(jobs.extendDeadline("other/scope", job.id(), Duration.ofSeconds(1)))
                .isFalse();
        jobs.kill(SCOPE, job.id());
    }

    @Test
    void scopes_areIsolated() {
        ExecJob job = jobs.waitFor(run("true", null), 10_000);

        assertThat(jobs.get(SCOPE, job.id())).containsSame(job);
        assertThat(jobs.get("other/scope", job.id())).isEmpty();
        assertThat(jobs.list(SCOPE)).containsExactly(job);
        assertThat(jobs.list("other/scope")).isEmpty();
        assertThat(jobs.stat("other/scope", job.id())).isEmpty();
    }

    @Test
    void tail_returnsTheLastLinesOldestFirst() throws Exception {
        ExecJob job = jobs.waitFor(run("for i in 1 2 3 4 5; do echo line$i; done", null), 10_000);
        waitUntil(() -> events.contains("finished:" + job.id()));

        assertThat(jobs.tail(SCOPE, job.id(), 2, ExecJobs.Stream.STDOUT)).containsExactly("line4", "line5");
        assertThat(jobs.tail(SCOPE, job.id(), 0, ExecJobs.Stream.STDOUT)).isEmpty();
        assertThatThrownBy(() -> jobs.tail("other/scope", job.id(), 2, ExecJobs.Stream.STDOUT))
                .isInstanceOf(ExecException.class)
                .hasMessageContaining("Unknown exec job");
    }

    @Test
    void stat_reportsSizesAndDuration() throws Exception {
        ExecJob job = jobs.waitFor(run("echo abc", null), 10_000);
        waitUntil(() -> events.contains("finished:" + job.id()));

        ExecStat stat = jobs.stat(SCOPE, job.id()).orElseThrow();

        assertThat(stat.status()).isEqualTo(ExecJob.Status.COMPLETED);
        assertThat(stat.stdoutBytes()).isEqualTo(4);
        assertThat(stat.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void overTheCap_theOldestTerminalJobIsEvictedAndItsDirRemoved() throws Exception {
        jobs.close();
        jobs = engine(50, 1);
        ExecJob first = jobs.waitFor(run("true", null), 10_000);
        waitUntil(() -> events.contains("finished:" + first.id()));
        Path firstDir = first.stdoutFile().getParent();

        ExecJob second = run("true", null);
        jobs.waitFor(second, 10_000);

        assertThat(events).contains("evicted:" + first.id());
        assertThat(jobs.get(SCOPE, first.id())).isEmpty();
        assertThat(firstDir).doesNotExist();
        assertThat(jobs.get(SCOPE, second.id())).isPresent();
    }

    @Test
    void reconcileOrphans_completesARunningJobWhoseProcessIsGone() throws Exception {
        ExecJob stuck = new ExecJob("deadbeef", "proj", "x", tmp.resolve("o.log"), tmp.resolve("e.log"));

        assertThat(ExecJobs.isStuckOrphan(stuck, Instant.now().plusSeconds(600), Duration.ofMinutes(2)))
                .as("no process, silent past the TTL")
                .isTrue();
        assertThat(ExecJobs.isStuckOrphan(stuck, Instant.now(), Duration.ofMinutes(2)))
                .as("recent output")
                .isFalse();
        assertThat(jobs.reconcileOrphans(Instant.now())).isZero();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10s");
            }
            Thread.sleep(20);
        }
    }
}
