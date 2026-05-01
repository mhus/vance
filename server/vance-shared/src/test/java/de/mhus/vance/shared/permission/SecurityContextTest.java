package de.mhus.vance.shared.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityContextTest {

    @Test
    void user_factory_buildsUserSubject() {
        SecurityContext ctx = SecurityContext.user("alice", "acme", List.of("admins"));

        assertThat(ctx.subjectType()).isEqualTo(SubjectType.USER);
        assertThat(ctx.subjectId()).isEqualTo("alice");
        assertThat(ctx.tenantId()).isEqualTo("acme");
        assertThat(ctx.teams()).containsExactly("admins");
    }

    @Test
    void teams_areDefensivelyCopied() {
        List<String> mutable = new ArrayList<>();
        mutable.add("dev");

        SecurityContext ctx = SecurityContext.user("alice", "acme", mutable);

        // mutate the source after construction; context must not see the change
        mutable.add("ops");

        assertThat(ctx.teams()).containsExactly("dev");
    }

    @Test
    void teams_areImmutable_afterConstruction() {
        SecurityContext ctx = SecurityContext.user("alice", "acme", List.of("dev"));

        assertThatThrownBy(() -> ctx.teams().add("ops"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void system_isSystemSubject() {
        assertThat(SecurityContext.SYSTEM.subjectType()).isEqualTo(SubjectType.SYSTEM);
        assertThat(SecurityContext.SYSTEM.teams()).isEmpty();
    }
}
