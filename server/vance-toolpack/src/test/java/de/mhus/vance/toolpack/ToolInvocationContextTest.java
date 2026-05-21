package de.mhus.vance.toolpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Trust-boundary checks for the home-vs-spot helpers on
 * {@link ToolInvocationContext}. The point of these helpers is that
 * tools resolve the project from the context, never from a tool param
 * the LLM may have hallucinated.
 */
class ToolInvocationContextTest {

    @Test
    void fiveArgConstructor_leavesWorkingProjectIdNull() {
        // Backwards-compat overload — legacy call-sites that don't know
        // about Eddie's spot pointer must still produce a valid context.
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "homeProj", "sess", "proc", "alice");

        assertThat(ctx.workingProjectId()).isNull();
        assertThat(ctx.projectId()).isEqualTo("homeProj");
    }

    @Test
    void sixArgConstructor_carriesWorkingProjectIdVerbatim() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "homeProj", "sess", "proc", "alice", "spotProj");

        assertThat(ctx.workingProjectId()).isEqualTo("spotProj");
    }

    @Test
    void resolveLocalProjectId_returnsHome_evenIfSpotIsSet() {
        // Home and spot live on the same record but resolve differently —
        // a home-bound tool must ignore the spot entirely.
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "homeProj", "sess", "proc", "alice", "spotProj");

        assertThat(ctx.resolveLocalProjectId()).isEqualTo("homeProj");
    }

    @Test
    void resolveLocalProjectId_throws_whenNoProjectBound() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", null, "sess", "proc", "alice");

        assertThatThrownBy(ctx::resolveLocalProjectId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void resolveLocalProjectId_throws_whenProjectIsBlank() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "  ", "sess", "proc", "alice");

        assertThatThrownBy(ctx::resolveLocalProjectId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireWorkingProjectId_returnsSpot_whenSet() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "homeProj", "sess", "proc", "alice", "spotProj");

        assertThat(ctx.requireWorkingProjectId()).isEqualTo("spotProj");
    }

    @Test
    void requireWorkingProjectId_throws_whenNoSpotSelected() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "homeProj", "sess", "proc", "alice", null);

        // Error message must mention SWITCH_PROJECT — the LLM reads
        // tool errors, this is the corrective hint.
        assertThatThrownBy(ctx::requireWorkingProjectId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SWITCH_PROJECT");
    }

    @Test
    void requireWorkingProjectId_throws_whenSpotIsBlank() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "homeProj", "sess", "proc", "alice", "   ");

        assertThatThrownBy(ctx::requireWorkingProjectId)
                .isInstanceOf(IllegalStateException.class);
    }
}
