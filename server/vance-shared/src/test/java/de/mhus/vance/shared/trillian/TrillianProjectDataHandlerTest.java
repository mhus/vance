package de.mhus.vance.shared.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.PermissionBootstrap;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A Trillian mints a real user. Nothing else in a project delete touches
 * {@code users} — it is tenant-scoped — so if this handler misses an account,
 * the account stays, hidden from the ordinary user listing, one per Trillian.
 */
class TrillianProjectDataHandlerTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final UserService userService = mock(UserService.class);
    private final PermissionBootstrap permissionBootstrap = mock(PermissionBootstrap.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PermissionBootstrap> bootstrapProvider =
            mock(ObjectProvider.class);

    private final TrillianProjectDataHandler handler = new TrillianProjectDataHandler(
            thinkProcessService, userService, bootstrapProvider);

    TrillianProjectDataHandlerTest() {
        // ObjectProvider.ifAvailable(consumer) — hand the mock through.
        doAnswerWithBootstrap();
    }

    @Test
    void delete_revokesGrantsBeforeRemovingTheAccount() {
        givenControlProcesses(control("_trillian-void-a7f3"));
        givenExistingUsers("_trillian-void-a7f3");

        assertThat(handler.delete("acme", "p1")).isEqualTo(1);

        // A grant keys on the user name, and a name comes back — a leftover
        // grant would be inherited by the next account minted under it.
        verify(permissionBootstrap).revokeAll("acme", "_trillian-void-a7f3");
        verify(userService).delete("acme", "_trillian-void-a7f3");
    }

    @Test
    void delete_releasesEveryGeneration_notJustTheNewest() {
        // A reactivate normally reuses the account; two distinct names mean an
        // earlier release did not happen, and that is exactly the leak this
        // handler is here for.
        givenControlProcesses(control("_trillian-void-old"), control("_trillian-void-new"));
        givenExistingUsers("_trillian-void-old", "_trillian-void-new");

        assertThat(handler.delete("acme", "p1")).isEqualTo(2);

        verify(userService).delete("acme", "_trillian-void-old");
        verify(userService).delete("acme", "_trillian-void-new");
    }

    @Test
    void delete_isIdempotent_onAnAccountThatIsAlreadyGone() {
        givenControlProcesses(control("_trillian-void-a7f3"));
        when(userService.findByTenantAndName("acme", "_trillian-void-a7f3"))
                .thenReturn(Optional.empty());

        assertThat(handler.delete("acme", "p1")).isZero();

        // Grants are still revoked: this doubles as the repair path for an
        // account whose deletion succeeded but whose grants did not.
        verify(permissionBootstrap).revokeAll("acme", "_trillian-void-a7f3");
        verify(userService, never()).delete("acme", "_trillian-void-a7f3");
    }

    @Test
    void delete_continues_whenOneAccountCannotBeRemoved() {
        givenControlProcesses(control("_trillian-void-bad"), control("_trillian-void-ok"));
        givenExistingUsers("_trillian-void-bad", "_trillian-void-ok");
        doThrow(new IllegalStateException("mongo down"))
                .when(userService).delete("acme", "_trillian-void-bad");

        // A project delete must not stall on one uncleanable account.
        assertThat(handler.delete("acme", "p1")).isEqualTo(1);
        verify(userService).delete("acme", "_trillian-void-ok");
    }

    @Test
    void delete_ignoresControlProcessesWithoutAnAccountName() {
        ThinkProcessDocument bare = ThinkProcessDocument.builder()
                .engineParams(Map.of("peerSessionId", "s-1"))
                .build();
        givenControlProcesses(bare);

        assertThat(handler.delete("acme", "p1")).isZero();
        verify(userService, never()).delete(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void count_onlyCountsAccountsThatStillExist() {
        givenControlProcesses(control("_trillian-void-gone"), control("_trillian-void-here"));
        givenExistingUsers("_trillian-void-here");
        when(userService.findByTenantAndName("acme", "_trillian-void-gone"))
                .thenReturn(Optional.empty());

        assertThat(handler.count("acme", "p1")).isEqualTo(1);
    }

    @Test
    void rename_doesNothing() {
        // The account name carries no project name; its grant is carried over
        // by the permission-grants handler and its attribute document by the
        // documents handler.
        assertThat(handler.rename("acme", "p1", "p2")).isZero();
        verify(thinkProcessService, never()).findAllByProjectAndEngine(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────

    private void givenControlProcesses(ThinkProcessDocument... processes) {
        when(thinkProcessService.findAllByProjectAndEngine(
                "acme", "p1", TrillianProcessKeys.CONTROL_ENGINE_NAME))
                .thenReturn(List.of(processes));
    }

    private void givenExistingUsers(String... names) {
        for (String name : names) {
            when(userService.findByTenantAndName("acme", name))
                    .thenReturn(Optional.of(UserDocument.builder()
                            .tenantId("acme").name(name).build()));
        }
    }

    private static ThinkProcessDocument control(String account) {
        return ThinkProcessDocument.builder()
                .thinkEngine(TrillianProcessKeys.CONTROL_ENGINE_NAME)
                .engineParams(Map.of(
                        TrillianProcessKeys.PARAM_TRILLIAN_USER_NAME, account))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void doAnswerWithBootstrap() {
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Consumer<PermissionBootstrap>) invocation.getArgument(0)).accept(permissionBootstrap);
            return null;
        }).when(bootstrapProvider).ifAvailable(org.mockito.ArgumentMatchers.any());
    }
}
