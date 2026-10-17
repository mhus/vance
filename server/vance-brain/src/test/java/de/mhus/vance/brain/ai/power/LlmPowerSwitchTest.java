package de.mhus.vance.brain.ai.power;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.llmusage.LlmPowerService;
import de.mhus.vance.shared.llmusage.LlmPowerState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link LlmPowerSwitch}: RAM on the call path, store in the background, failures keep the last state. */
class LlmPowerSwitchTest {

    private static final String TENANT = "acme";
    private static final LlmPowerState OFF = new LlmPowerState(new LlmPowerState.Switch(null, null), Map.of());

    private final LlmPowerService service = mock(LlmPowerService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:17Z"));
    private final LlmPowerSwitch powerSwitch = new LlmPowerSwitch(service, LlmPowerSwitch.DEFAULT_CRON, clock);

    @Test
    void state_loadsOnce_thenServesFromRam() {
        when(service.load(TENANT)).thenReturn(OFF);

        assertThat(powerSwitch.state(TENANT).tenantOn()).isFalse();
        assertThat(powerSwitch.state(TENANT).tenantOn()).isFalse();

        verify(service, times(1)).load(TENANT);
    }

    @Test
    void state_firstLoadFails_admits_andTriesAgainNextCall() {
        when(service.load(TENANT))
                .thenThrow(new IllegalStateException("mongo down"))
                .thenReturn(OFF);

        assertThat(powerSwitch.state(TENANT).tenantOn()).isTrue();
        assertThat(powerSwitch.cached(TENANT)).isNull();
        assertThat(powerSwitch.state(TENANT).tenantOn()).isFalse();
    }

    @Test
    void refresh_picksUpAChangeFromAnotherPod() {
        when(service.load(TENANT)).thenReturn(LlmPowerState.ALL_ON, OFF);
        powerSwitch.state(TENANT);

        powerSwitch.refresh();

        assertThat(powerSwitch.state(TENANT).tenantOn()).isFalse();
    }

    @Test
    void refresh_failure_keepsTheLastState() {
        when(service.load(TENANT)).thenReturn(OFF).thenThrow(new IllegalStateException("mongo down"));
        powerSwitch.state(TENANT);

        powerSwitch.refresh();

        assertThat(powerSwitch.state(TENANT).tenantOn()).isFalse();
    }

    @Test
    void refresh_dropsTenantsNotAskedForAWhile() {
        when(service.load(TENANT)).thenReturn(OFF);
        powerSwitch.state(TENANT);
        clock.now = clock.now.plus(LlmPowerSwitch.IDLE_EVICTION).plusSeconds(1);

        powerSwitch.refresh();

        assertThat(powerSwitch.cached(TENANT)).isNull();
    }

    @Test
    void setTenantPower_appliesOnThisPodAtOnce() {
        when(service.load(TENANT)).thenReturn(LlmPowerState.ALL_ON, OFF);
        powerSwitch.state(TENANT);

        powerSwitch.setTenantPower(TENANT, false, "marvin");

        verify(service).setTenantPower(TENANT, false, "marvin");
        assertThat(powerSwitch.state(TENANT).tenantOn()).isFalse();
    }

    @Test
    void stored_readsTheStore_withoutTouchingTheCache() {
        when(service.load(TENANT)).thenReturn(LlmPowerState.ALL_ON, OFF);
        powerSwitch.state(TENANT);

        assertThat(powerSwitch.stored(TENANT).tenantOn()).isFalse();
        assertThat(powerSwitch.cached(TENANT)).isEqualTo(LlmPowerState.ALL_ON);
    }

    @Test
    void verifyAt_isTheNextFullMinutePlusGrace() {
        assertThat(powerSwitch.nextRefreshAt()).isEqualTo(Instant.parse("2026-10-17T10:01:00Z"));
        assertThat(powerSwitch.verifyAt()).isEqualTo(Instant.parse("2026-10-17T10:01:02Z"));
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
