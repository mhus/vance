package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BusyIndicatorEdgeTest {

    private static final class RecordingListener implements BusyListener {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();

        @Override
        public void onBusyStart() {
            starts.incrementAndGet();
        }

        @Override
        public void onBusyEnd() {
            ends.incrementAndGet();
        }
    }

    @Test
    void nestedEnterExit_firesEdgesOnceEach() {
        RecordingListener l = new RecordingListener();
        BusyIndicator busy = new BusyIndicator(List.of(l));

        busy.enter("outer");
        busy.enter("inner");
        busy.exit("inner");
        busy.exit("outer");

        assertThat(l.starts.get()).isEqualTo(1);
        assertThat(l.ends.get()).isEqualTo(1);
    }

    @Test
    void clear_firesEndWhenWorkWasInFlight() {
        RecordingListener l = new RecordingListener();
        BusyIndicator busy = new BusyIndicator(List.of(l));

        busy.enter("chat");
        busy.clear();

        assertThat(l.starts.get()).isEqualTo(1);
        assertThat(l.ends.get()).isEqualTo(1);
        assertThat(busy.isBusy()).isFalse();
    }

    @Test
    void clear_whenIdle_doesNotFireEnd() {
        RecordingListener l = new RecordingListener();
        BusyIndicator busy = new BusyIndicator(List.of(l));

        busy.clear();

        assertThat(l.ends.get()).isZero();
    }

    @Test
    void throwingListener_doesNotBreakCounter() {
        BusyListener boom = new BusyListener() {
            @Override
            public void onBusyStart() {
                throw new RuntimeException("boom");
            }

            @Override
            public void onBusyEnd() {
                throw new RuntimeException("boom");
            }
        };
        BusyIndicator busy = new BusyIndicator(List.of(boom));

        busy.enter("x");
        assertThat(busy.isBusy()).isTrue();
        busy.exit("x");
        assertThat(busy.isBusy()).isFalse();
    }
}
