package de.mhus.vance.shared.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AllowAllPermissionResolverTest {

    private final AllowAllPermissionResolver resolver = new AllowAllPermissionResolver();

    @Test
    void permitsEveryUserCheck() {
        SecurityContext alice = SecurityContext.user("alice", "acme", List.of());
        Resource res = new Resource.Document("acme", "proj", "foo.md");

        for (Action action : Action.values()) {
            assertThat(resolver.isAllowed(alice, res, action))
                    .as("AllowAll must permit %s", action)
                    .isTrue();
        }
    }

    @Test
    void permitsSystemSubject() {
        assertThat(resolver.isAllowed(
                SecurityContext.SYSTEM,
                new Resource.Tenant("acme"),
                Action.ADMIN))
                .isTrue();
    }
}
