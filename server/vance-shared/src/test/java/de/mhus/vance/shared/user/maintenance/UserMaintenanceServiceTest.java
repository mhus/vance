package de.mhus.vance.shared.user.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.maintenance.MaintenanceReport;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The rules that make deleting an account safe to run twice and unsafe to run
 * blindly. The handlers are fakes — this is about the orchestration.
 */
class UserMaintenanceServiceTest {

    private final UserService userService = mock(UserService.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    @Test
    void delete_runsHandlersInOrderAscending() {
        List<String> calls = new ArrayList<>();
        FakeHandler hub = new FakeHandler("hub", 50, 1).recording(calls);
        FakeHandler sessions = new FakeHandler("sessions", 500, 4).recording(calls);
        UserMaintenanceService service = service(List.of(sessions, hub));

        service.delete("acme", "mhus", false);

        assertThat(calls).containsExactly("hub", "sessions");
    }

    @Test
    void delete_removesTheAccount_whenEveryHandlerSucceeded() {
        UserMaintenanceService service = service(List.of(new FakeHandler("sessions", 500, 4)));

        MaintenanceReport report = service.delete("acme", "mhus", false);

        verify(userService).delete("acme", "mhus");
        assertThat(report.total()).isEqualTo(5); // 4 rows + the account document
    }

    @Test
    void delete_keepsTheAccount_whenAnEntityFailed() {
        FakeHandler broken = new FakeHandler("grants", 500, 0);
        broken.failure = new IllegalStateException("mongo down");
        UserMaintenanceService service = service(List.of(broken));

        MaintenanceReport report = service.delete("acme", "mhus", false);

        // Keeping the document keeps the login taken until the cleanup is done
        // — which is what stops a new account inheriting the leftovers.
        verify(userService, never()).delete("acme", "mhus");
        assertThat(report.entities())
                .anySatisfy(e -> assertThat(e.note()).contains("mongo down"))
                .anySatisfy(e -> assertThat(e.handlerId()).isEqualTo("user"));
    }

    @Test
    void delete_refuses_whenAHandlerSaysTheAccountIsInUse() {
        FakeHandler guard = new FakeHandler("trillian-guard", 10, 0);
        guard.deleteBlocker = "2 Trillian control processes run as this account";
        UserMaintenanceService service = service(List.of(guard));

        assertThatThrownBy(() -> service.delete("acme", "_trillian-void-a7", false))
                .isInstanceOf(UserMaintenanceService.UserInUseException.class)
                .hasMessageContaining("Trillian control processes");
        verify(userService, never()).delete("acme", "_trillian-void-a7");
    }

    @Test
    void delete_proceedsOnForce_pastAnInUseBlocker() {
        FakeHandler guard = new FakeHandler("trillian-guard", 10, 0);
        guard.deleteBlocker = "still running";
        UserMaintenanceService service = service(List.of(guard));

        service.delete("acme", "_trillian-void-a7", true);

        verify(userService).delete("acme", "_trillian-void-a7");
    }

    @Test
    void rename_writesNothing_whenAnyHandlerBlocks() {
        FakeHandler blocking = new FakeHandler("hub", 50, 0);
        blocking.renameBlocker = "a hub for that login already exists";
        FakeHandler willing = new FakeHandler("sessions", 500, 3);
        UserMaintenanceService service = service(List.of(blocking, willing));

        assertThatThrownBy(() -> service.rename("acme", "mhus", "mike"))
                .isInstanceOf(UserMaintenanceService.RenameBlockedException.class)
                .hasMessageContaining("hub for that login");
        assertThat(willing.renamed).isFalse();
        verify(userService, never()).rename("acme", "mhus", "mike");
    }

    @Test
    void rename_refuses_aNameThatLooksLikeATombstone() {
        UserMaintenanceService service = service(List.of());

        // The marker means "this name belonged to somebody who is gone". Giving
        // it to a live account makes every future reader wrong about which is
        // which.
        assertThatThrownBy(() -> service.rename("acme", "mhus", "_deleted_someone"))
                .isInstanceOf(UserService.ReservedNameException.class);
    }

    @Test
    void rename_renamesTheAccountLast() {
        FakeHandler sessions = new FakeHandler("sessions", 500, 3);
        UserMaintenanceService service = service(List.of(sessions));

        service.rename("acme", "mhus", "mike");

        assertThat(sessions.renamed).isTrue();
        verify(userService).rename("acme", "mhus", "mike");
    }

    @Test
    void inspect_writesNothing() {
        FakeHandler sessions = new FakeHandler("sessions", 500, 9);
        UserMaintenanceService service = service(List.of(sessions));

        MaintenanceReport report = service.inspect("acme", "mhus");

        assertThat(report.total()).isEqualTo(9);
        assertThat(sessions.deleted).isFalse();
        verify(userService, never()).delete("acme", "mhus");
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────

    private UserMaintenanceService service(List<UserDataHandler> handlers) {
        when(userService.findByTenantAndName(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new UserDocument()));
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of());
        return new UserMaintenanceService(handlers, userService, mongoTemplate);
    }

    /** A handler that records what it was asked and can be told to misbehave. */
    private static final class FakeHandler implements UserDataHandler {
        private final String id;
        private final int order;
        private final long rows;
        private @Nullable List<String> calls;
        private @Nullable RuntimeException failure;
        private @Nullable String deleteBlocker;
        private @Nullable String renameBlocker;
        private boolean deleted;
        private boolean renamed;

        FakeHandler(String id, int order, long rows) {
            this.id = id;
            this.order = order;
            this.rows = rows;
        }

        FakeHandler recording(List<String> calls) {
            this.calls = calls;
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> collections() {
            return Set.of(id);
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public long count(String tenantId, String userName) {
            return rows;
        }

        @Override
        public long delete(String tenantId, String userName) {
            if (calls != null) {
                calls.add(id);
            }
            if (failure != null) {
                throw failure;
            }
            deleted = true;
            return rows;
        }

        @Override
        public long rename(String tenantId, String userName, String newUserName) {
            renamed = true;
            return rows;
        }

        @Override
        public @Nullable String deleteBlocker(String tenantId, String userName) {
            return deleteBlocker;
        }

        @Override
        public @Nullable String renameBlocker(
                String tenantId, String userName, String newUserName) {
            return renameBlocker;
        }
    }
}
