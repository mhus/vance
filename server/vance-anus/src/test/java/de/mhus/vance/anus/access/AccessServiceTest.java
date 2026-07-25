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
    void boot_withBlankHash_disablesLoginEntirely() {
        // No credential configured → there is NO default fallback. Every login
        // attempt fails and the service flags login as unconfigured/disabled.
        AccessProperties empty = new AccessProperties();
        empty.setPasswordHash("   ");

        AccessService disabled = new AccessService(empty, auditService);

        assertThat(disabled.isLoginConfigured()).isFalse();
        assertThat(disabled.isLoginDisabledWarning()).isTrue();
        assertThat(disabled.login("anything")).isFalse();
        assertThat(disabled.login("vance-anus-login")).isFalse();
        assertThat(disabled.isAuthorized()).isFalse();
    }

    @Test
    void boot_withConfiguredHash_reportsLoginEnabled() {
        // Sanity: a configured credential enables login and shows no warning.
        assertThat(service.isLoginConfigured()).isTrue();
        assertThat(service.isLoginDisabledWarning()).isFalse();
    }

    @Test
    void login_noopPlaintextCredential_armsWindow() {
        // {noop} prefix = plaintext credential, the zero-friction path for ops.
        AccessProperties noop = new AccessProperties();
        noop.setPasswordHash("{noop}plain-secret");
        AccessService svc = new AccessService(noop, auditService);

        assertThat(svc.isLoginConfigured()).isTrue();
        assertThat(svc.login("wrong")).isFalse();
        assertThat(svc.login("plain-secret")).isTrue();
        assertThat(svc.isAuthorized()).isTrue();
    }

    @Test
    void login_bcryptPrefixedCredential_armsWindow() {
        // {bcrypt} prefix = hashed-at-rest credential, as minted by `hash`.
        AccessProperties bcrypt = new AccessProperties();
        bcrypt.setPasswordHash("{bcrypt}" + new BCryptPasswordEncoder(4).encode(SECRET));
        AccessService svc = new AccessService(bcrypt, auditService);

        assertThat(svc.login("wrong")).isFalse();
        assertThat(svc.login(SECRET)).isTrue();
        assertThat(svc.isAuthorized()).isTrue();
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
    void armForSudo_suppressesLoginDisabledWarning() {
        AccessProperties empty = new AccessProperties();
        empty.setPasswordHash(null);
        AccessService disabled = new AccessService(empty, auditService);

        // Before sudo-arm: warning is on (no credential configured).
        assertThat(disabled.isLoginDisabledWarning()).isTrue();

        disabled.armForSudo();

        // In sudo-mode the warning is irrelevant — sudo arms the window without
        // a login, and the process exits after the requested commands.
        assertThat(disabled.isLoginDisabledWarning()).isFalse();
        assertThat(disabled.isAuthorized()).isTrue();
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
