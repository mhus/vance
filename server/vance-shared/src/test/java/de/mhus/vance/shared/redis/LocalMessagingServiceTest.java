package de.mhus.vance.shared.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalMessagingServiceTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-03T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MutableClock clock;
    private LocalMessagingService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new LocalMessagingService(clock);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void isNotCrossPod() {
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void deliversOnAnotherThreadThanThePublisher() throws Exception {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CountDownLatch got = new CountDownLatch(1);
        service.subscribe("acme", "documents", (topic, payload) -> {
            handlerThread.set(Thread.currentThread());
            got.countDown();
        });

        service.publish("acme", "documents", "x");

        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handlerThread.get()).isNotSameAs(Thread.currentThread());
    }

    @Test
    void aSlowHandlerDoesNotBlockThePublisher() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        service.subscribe("acme", "slow", (t, p) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long start = System.nanoTime();
        service.publish("acme", "slow", "1");
        service.publish("acme", "slow", "2");
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        release.countDown();

        assertThat(tookMs).isLessThan(1000);
    }

    @Test
    void aHandlerThatPublishesAgainDoesNotRecurse() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        service.subscribe("acme", "ping", (t, p) -> {
            seen.add("ping:" + p);
            service.publish("acme", "pong", p);
            done.countDown();
        });
        service.subscribe("acme", "pong", (t, p) -> {
            seen.add("pong:" + p);
            done.countDown();
        });

        service.publish("acme", "ping", "a");

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly("ping:a", "pong:a");
    }

    @Test
    void keepsPublishOrder() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(50);
        service.subscribe("acme", "seq", (t, p) -> {
            seen.add(p);
            done.countDown();
        });

        for (int i = 0; i < 50; i++) service.publish("acme", "seq", String.valueOf(i));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 50; i++) assertThat(seen.get(i)).isEqualTo(String.valueOf(i));
    }

    @Test
    void aFailingHandlerDoesNotStopTheOthers() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        service.subscribe("acme", "a", (t, p) -> {
            throw new IllegalStateException("boom");
        });
        service.subscribe("acme", "b", (t, p) -> got.countDown());

        service.publish("acme", "a", "x");
        service.publish("acme", "b", "y");

        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void acrossTenantsPatternSeesEveryTenantAndTheTopic() throws Exception {
        List<String> topics = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        service.subscribeAcrossTenants("documents:presence", (t, p) -> {
            topics.add(t);
            done.countDown();
        });

        service.publish("acme", "documents:presence", "1");
        service.publish("beta", "documents:presence", "2");
        service.publish("acme", "other", "3");

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(topics).containsExactly("vance:acme:documents:presence", "vance:beta:documents:presence");
        assertThat(VanceRedisMessagingService.tenantFromTopic(topics.get(1))).isEqualTo("beta");
    }

    @Test
    void tenantScopedSubscriberDoesNotSeeOtherTenants() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        service.subscribe("acme", "c", (t, p) -> {
            seen.add(p);
            done.countDown();
        });

        service.publish("beta", "c", "wrong");
        service.publish("acme", "c", "right");

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly("right");
    }

    @Test
    void globalChannelAndUnsubscribe() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        service.subscribeGlobal("lifecycle", (t, p) -> {
            seen.add(p);
            first.countDown();
        });
        service.publishGlobal("lifecycle", "up");
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();

        service.unsubscribeGlobal("lifecycle");
        service.publishGlobal("lifecycle", "down");
        CountDownLatch barrier = new CountDownLatch(1);
        service.subscribeGlobal("barrier", (t, p) -> barrier.countDown());
        service.publishGlobal("barrier", "b");
        assertThat(barrier.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seen).containsExactly("up");
    }

    @Test
    void hashHoldsFieldsUntilTheTtlRunsOut() {
        service.hashPut("acme", "roster", "a", "1", Duration.ofSeconds(60));
        service.hashPut("acme", "roster", "b", "2", Duration.ofSeconds(60));
        assertThat(service.hashGetAll("acme", "roster")).containsExactlyInAnyOrderEntriesOf(Map.of("a", "1", "b", "2"));

        clock.now = clock.now.plusSeconds(61);
        assertThat(service.hashGetAll("acme", "roster")).isEmpty();
    }

    @Test
    void hashPutRefreshesTheKeyTtl() {
        service.hashPut("acme", "roster", "a", "1", Duration.ofSeconds(60));
        clock.now = clock.now.plusSeconds(50);
        service.hashPut("acme", "roster", "a", "1", Duration.ofSeconds(60));
        clock.now = clock.now.plusSeconds(50);

        assertThat(service.hashGetAll("acme", "roster")).containsEntry("a", "1");
    }

    @Test
    void hashRefreshTtlOnlyExtendsAnExistingKey() {
        service.hashRefreshTtl("acme", "missing", Duration.ofSeconds(60));
        assertThat(service.hashGetAll("acme", "missing")).isEmpty();

        service.hashPut("acme", "roster", "a", "1", Duration.ofSeconds(10));
        service.hashRefreshTtl("acme", "roster", Duration.ofSeconds(100));
        clock.now = clock.now.plusSeconds(50);
        assertThat(service.hashGetAll("acme", "roster")).containsEntry("a", "1");
    }

    @Test
    void hashDeleteRemovesTheFieldAndTheEmptyKey() {
        service.hashPut("acme", "roster", "a", "1", Duration.ofSeconds(60));
        service.hashPut("acme", "roster", "b", "2", Duration.ofSeconds(60));

        service.hashDelete("acme", "roster", "a");
        assertThat(service.hashGetAll("acme", "roster")).containsOnlyKeys("b");

        service.hashDelete("acme", "roster", "b");
        assertThat(service.hashGetAll("acme", "roster")).isEmpty();
    }

    @Test
    void hashesAreScopedPerTenant() {
        service.hashPut("acme", "roster", "a", "1", Duration.ofSeconds(60));
        assertThat(service.hashGetAll("beta", "roster")).isEmpty();
    }

    @Test
    void valuesExpireAndPutIfAbsentIsExclusive() {
        assertThat(service.valuePutIfAbsent("acme", "lock", "a", Duration.ofSeconds(60)))
                .isTrue();
        assertThat(service.valuePutIfAbsent("acme", "lock", "b", Duration.ofSeconds(60)))
                .isFalse();
        assertThat(service.valueGet("acme", "lock")).contains("a");
        assertThat(service.valueExists("acme", "lock")).isTrue();

        clock.now = clock.now.plusSeconds(61);
        assertThat(service.valueGet("acme", "lock")).isEmpty();
        assertThat(service.valueExists("acme", "lock")).isFalse();
        assertThat(service.valuePutIfAbsent("acme", "lock", "b", Duration.ofSeconds(60)))
                .isTrue();
    }

    @Test
    void valuePutReplacesAndDeleteRemoves() {
        service.valuePut("acme", "v", "1", Duration.ofSeconds(60));
        service.valuePut("acme", "v", "2", Duration.ofSeconds(60));
        assertThat(service.valueGet("acme", "v")).contains("2");
        assertThat(service.valueGet("beta", "v")).isEmpty();

        service.valueDelete("acme", "v");
        assertThat(service.valueGet("acme", "v")).isEmpty();
    }
}
