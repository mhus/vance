package de.mhus.vance.brain.applications;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An application builds its permission subject from {@code ctx.toolContext()}; that view has to keep the
 * credential confinement the caller came with, or a pinned token could create and refresh through an app what it
 * could not touch directly.
 */
class VanceApplicationContextTest {

    @Test
    void a_create_context_hands_its_confinement_to_the_tool_view() {
        VanceApplication.CreateContext ctx =
                new VanceApplication.CreateContext("acme", "proj", "apps/x", "alice", "p-1", false, Map.of(), "proj");

        ToolInvocationContext tool = ctx.toolContext();

        assertThat(tool.tenantId()).isEqualTo("acme");
        assertThat(tool.projectId()).isEqualTo("proj");
        assertThat(tool.userId()).isEqualTo("alice");
        assertThat(tool.processId()).isEqualTo("p-1");
        assertThat(tool.confinedToProject()).isEqualTo("proj");
    }

    @Test
    void a_refresh_context_hands_its_confinement_to_the_tool_view() {
        VanceApplication.RefreshContext ctx =
                new VanceApplication.RefreshContext("acme", "proj", "apps/x", "alice", null, "proj");

        assertThat(ctx.toolContext().confinedToProject()).isEqualTo("proj");
        assertThat(ctx.toolContext().userId()).isEqualTo("alice");
    }

    @Test
    void the_short_constructors_leave_the_context_unconfined() {
        VanceApplication.CreateContext create =
                new VanceApplication.CreateContext("acme", "proj", "apps/x", "alice", null, true, Map.of());
        VanceApplication.RefreshContext refresh =
                new VanceApplication.RefreshContext("acme", "proj", "apps/x", "alice", null);

        assertThat(create.confinedToProject()).isNull();
        assertThat(refresh.confinedToProject()).isNull();
        assertThat(create.toolContext().confinedToProject()).isNull();
    }
}
