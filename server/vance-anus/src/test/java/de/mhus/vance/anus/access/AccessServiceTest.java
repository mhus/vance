package de.mhus.vance.anus.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.audit.AuditServiceProperties;
import de.mhus.vance.shared.metric.MetricService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AccessServiceTest {

    // Deliberately different from AccessService.DEFAULT_PASSWORD so the
    // "configured hash does not silently accept the default" test is meaningful.
    private static final String SECRET = "test-correct-horse-battery-staple";
    private AccessProperties props;
    private AccessService service;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        props = new AccessProperties();
        props.setPasswordHash(new BCryptPasswordEncoder(4).encode(SECRET));
        props.setTimeout(Duration.ofMinutes(5));
        auditService = noopAudit();
        service = new AccessService(props, auditService);
    }

    private static AuditService noopAudit() {
        // SYNC default + empty consumer list = no-op; @PostConstruct is
        // not invoked under direct construction, which is fine — mode is
        // already SYNC from the field initializer.
        return new AuditService(new AuditServiceProperties(),
                new MetricService(new SimpleMeterRegistry()), List.of());
    }

    @Test
    void boot_withBlankHash_fallsBackToV1DefaultPassword() {
        // No hash configured → service must accept the v1 default plaintext
        // password and flag itself as running on the default. Wrong passwords
        // are still rejected.
        AccessProperties empty = new AccessProperties();
        empty.setPasswordHash("   ");

        AccessService fallback = new AccessService(empty, auditService);

        assertThat(fallback.isUsingDefaultPassword()).isTrue();
        assertThat(fallback.login("anything")).isFalse();
        assertThat(fallback.login(AccessService.DEFAULT_PASSWORD)).isTrue();
        assertThat(fallback.isAuthorized()).isTrue();
    }

    @Test
    void boot_withConfiguredHash_doesNotAcceptDefaultPassword() {
        // Sanity: configured hash must NOT silently accept the v1 default.
        assertThat(service.isUsingDefaultPassword()).isFalse();
        assertThat(service.login(AccessService.DEFAULT_PASSWORD)).isFalse();
        assertThat(service.isAuthorized()).isFalse();
    }

    @Test
    void login_correctPassword_armsWindow() {
        assertThat(service.isAuthorized()).isFalse();

        boolean ok = service.login(SECRET);

        assertThat(ok).isTrue();
        assertThat(service.isAuthorized()).isTrue();
        assertThat(service.remaining()).isPositive();
    }

    @Test
    void login_wrongPassword_returnsFalseAndLeavesWindowClosed() {
        boolean ok = service.login("nope");

        assertThat(ok).isFalse();
        assertThat(service.isAuthorized()).isFalse();
    }

    @Test
    void login_blankPassword_isRejectedWithoutBcryptCall() {
        assertThat(service.login("")).isFalse();
        assertThat(service.login("   ")).isFalse();
        assertThat(service.isAuthorized()).isFalse();
    }

    @Test
    void requireAuthorized_withoutLogin_throws() {
        assertThatThrownBy(() -> service.requireAuthorized())
                .isInstanceOf(NotAuthorizedException.class)
                .hasMessageContaining("login");
    }

    @Test
    void requireAuthorized_extendsTheWindow() {
        // Tiny timeout so the slide is observable.
        props.setTimeout(Duration.ofSeconds(2));
        assertThat(service.login(SECRET)).isTrue();

        Duration before = service.remaining();
        // Walk the clock forward a tick by busy-waiting so 'remaining' shrinks.
        sleepMillis(50);
        service.requireAuthorized();
        Duration after = service.remaining();

        // Sliding window: after the call, remaining must be ≥ before — the
        // call refreshed the deadline. Equality is allowed if both samples
        // landed in the same millisecond.
        assertThat(after).isGreaterThanOrEqualTo(before);
    }

    @Test
    void logout_clearsTheWindow() {
        service.login(SECRET);
        assertThat(service.isAuthorized()).isTrue();

        service.logout();

        assertThat(service.isAuthorized()).isFalse();
        assertThat(service.remaining()).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> service.requireAuthorized())
                .isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void armForSudo_armsWindowWithoutPasswordCheck() {
        assertThat(service.isAuthorized()).isFalse();
        assertThat(service.isSudoMode()).isFalse();

        service.armForSudo();

        assertThat(service.isAuthorized()).isTrue();
        assertThat(service.isSudoMode()).isTrue();
        // requireAuthorized() must succeed after sudo-arm — same gate as login.
        service.requireAuthorized();
    }

    @Test
    void armForSudo_suppressesDefaultPasswordWarning() {
        AccessProperties empty = new AccessProperties();
        empty.setPasswordHash(null);
        AccessService fallback = new AccessService(empty, auditService);

        // Before sudo-arm: warning is on (fresh install on the v1 default).
        assertThat(fallback.isUsingDefaultPassword()).isTrue();

        fallback.armForSudo();

        // In sudo-mode the warning is irrelevant — process exits after the
        // requested commands, there is no shell left to leave open.
        assertThat(fallback.isUsingDefaultPassword()).isFalse();
    }

    @Test
    void armForSudo_thenLogout_clearsWindowAndStaysQuiet() {
        service.armForSudo();
        assertThat(service.isAuthorized()).isTrue();

        service.logout();

        assertThat(service.isAuthorized()).isFalse();
        // sudoMode is a one-way arm marker for the current window — once
        // logout drops the window, requireAuthorized() must throw again.
        assertThatThrownBy(() -> service.requireAuthorized())
                .isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void requireAuthorized_afterTimeout_throwsAndClearsState() {
        props.setTimeout(Duration.ofMillis(50));
        service.login(SECRET);
        assertThat(service.isAuthorized()).isTrue();

        sleepMillis(120);

        assertThatThrownBy(() -> service.requireAuthorized())
                .isInstanceOf(NotAuthorizedException.class);
        assertThat(service.isAuthorized()).isFalse();
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
