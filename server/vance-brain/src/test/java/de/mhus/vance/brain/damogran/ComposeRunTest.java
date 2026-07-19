package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ComposeRunTest {

    private ComposeRun newRun() {
        return new ComposeRun("cr-1", "t", "p", "ws", Instant.now());
    }

    @Test
    void onDone_firesWhenRunCompletes() {
        ComposeRun run = newRun();
        AtomicReference<ComposeRun> got = new AtomicReference<>();
        run.onDone(got::set);

        assertThat(got.get()).isNull();
        run.complete(new DamogranComposeResult(DamogranStatus.SUCCESS, "ws", List.of(), null));

        assertThat(got.get()).isSameAs(run);
        assertThat(run.status()).isEqualTo(ComposeRun.Status.SUCCESS);
    }

    @Test
    void onDone_firesImmediatelyIfAlreadyTerminal() {
        ComposeRun run = newRun();
        run.fail("boom");

        AtomicReference<ComposeRun> got = new AtomicReference<>();
        run.onDone(got::set);

        assertThat(got.get()).isSameAs(run);
        assertThat(run.status()).isEqualTo(ComposeRun.Status.FAILURE);
    }
}
