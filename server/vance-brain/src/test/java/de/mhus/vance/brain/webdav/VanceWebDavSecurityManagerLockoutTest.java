package de.mhus.vance.brain.webdav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lockout accounting on the WebDAV Basic-Auth path.
 *
 * <p>The hazard this pins down: a WebDAV client re-sends credentials on
 * every single request and retries hard. A password gone stale in Finder
 * or Obsidian would burn the whole 5-attempt lockout budget within
 * seconds and lock the account out of the web UI too — a self-inflicted
 * denial of service on an ordinary user path. Repeats of the <em>same</em>
 * wrong credential are therefore counted once per window, while distinct
 * guesses each count, because that is what a guessing attack looks like.
 *
 * <p>{@link PasswordService} is mocked rather than real: the verdict is
 * all this class consumes, and a real BCrypt verify at cost 12 would put
 * seconds on a test that retries twenty times.
 */
class VanceWebDavSecurityManagerLockoutTest {

    private static final String TENANT = "acme";
    private static final String USER = "wile.coyote";
    private static final String GOOD = "correct-horse-battery";
    private static final String HASH = "$2a$12$storedhash";

    /** Mirrors {@code FAILURE_DEDUP_WINDOW_MS}. */
    private static final long WINDOW_MS = 60_000L;

    private PasswordService passwordService;
    private UserService userService;
    private AtomicLong now;
    private VanceWebDavSecurityManager manager;

    @BeforeEach
    void setUp() {
        passwordService = mock(PasswordService.class);
        when(passwordService.verify(anyString(), eq(HASH))).thenReturn(false);
        when(passwordService.verify(eq(GOOD), eq(HASH))).thenReturn(true);

        userService = mock(UserService.class);
        TeamService teamService = mock(TeamService.class);
        when(teamService.byMember(TENANT, USER)).thenReturn(List.of());
        now = new AtomicLong(1_000_000L);
        manager = new VanceWebDavSecurityManager(
                passwordService, userService, teamService,
                mock(PermissionService.class), new WebDavProperties(), now::get);

        UserDocument doc = new UserDocument();
        doc.setTenantId(TENANT);
        doc.setName(USER);
        doc.setStatus(UserStatus.ACTIVE);
        doc.setLoginEnabled(true);
        doc.setPasswordHash(HASH);
        when(userService.findByTenantAndName(TENANT, USER)).thenReturn(Optional.of(doc));
        when(userService.isLocked(doc)).thenReturn(false);
    }

    @Test
    void sameWrongPasswordRetried_countsOnce() {
        for (int i = 0; i < 20; i++) {
            now.addAndGet(200);
            assertThat(manager.authenticate(TENANT, USER, "stale-cached-password")).isNull();
        }

        verify(userService, times(1)).recordFailedLogin(TENANT, USER);
    }

    @Test
    void sameWrongPasswordStillRetrying_countsAgainOncePerWindow() {
        // The storm must not slide the window along with it: a client
        // retrying every few seconds forever has to keep showing up in the
        // failure count, once per window, or a permanently misconfigured
        // client is invisible.
        for (int i = 0; i < 3 * (WINDOW_MS / 1_000); i++) {
            now.addAndGet(1_000);
            manager.authenticate(TENANT, USER, "stale-cached-password");
        }

        verify(userService, times(3)).recordFailedLogin(TENANT, USER);
    }

    @Test
    void distinctWrongPasswords_eachCount() {
        manager.authenticate(TENANT, USER, "guess-1");
        manager.authenticate(TENANT, USER, "guess-2");
        manager.authenticate(TENANT, USER, "guess-3");

        verify(userService, times(3)).recordFailedLogin(TENANT, USER);
    }

    @Test
    void successAfterFailure_letsTheSameCredentialCountAgain() {
        // Forgetting the fingerprint on success matters: the user fixed
        // the password, and a later failure with that same string is a
        // new event, not a repeat of the old storm.
        manager.authenticate(TENANT, USER, GOOD);
        manager.authenticate(TENANT, USER, "wrong");
        manager.authenticate(TENANT, USER, GOOD);
        manager.authenticate(TENANT, USER, "wrong");

        verify(userService, times(2)).recordFailedLogin(TENANT, USER);
    }

    @Test
    void correctPassword_neverCountsAFailure() {
        assertThat(manager.authenticate(TENANT, USER, GOOD)).isNotNull();

        verify(userService, never()).recordFailedLogin(TENANT, USER);
    }

    @Test
    void unknownUser_isNotTracked() {
        when(userService.findByTenantAndName(TENANT, "road.runner")).thenReturn(Optional.empty());

        assertThat(manager.authenticate(TENANT, "road.runner", "beep")).isNull();

        verify(userService, never()).recordFailedLogin(any(), any());
    }
}
