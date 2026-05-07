package de.mhus.vance.foot.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientExecJobScopeTest {

    @Test
    void scopelessConstructor_leavesFieldsNull() {
        ClientExecJob job = new ClientExecJob(
                "id", "true", Path.of("o.log"), Path.of("e.log"));

        assertThat(job.sessionId()).isNull();
        assertThat(job.projectId()).isNull();
    }

    @Test
    void scopedConstructor_keepsSessionAndProject() {
        ClientExecJob job = new ClientExecJob(
                "id", "true", Path.of("o.log"), Path.of("e.log"),
                "sess-1", "proj-x");

        assertThat(job.sessionId()).isEqualTo("sess-1");
        assertThat(job.projectId()).isEqualTo("proj-x");
    }

    @Test
    void renderer_emitsScopeFieldsWhenSet() {
        ClientExecJob job = new ClientExecJob(
                "id", "true", Path.of("o.log"), Path.of("e.log"),
                "sess-1", "proj-x");

        var rendered = ClientExecJobRenderer.render(job);

        assertThat(rendered).containsEntry("sessionId", "sess-1");
        assertThat(rendered).containsEntry("projectId", "proj-x");
    }

    @Test
    void renderer_omitsScopeFieldsWhenNull() {
        ClientExecJob job = new ClientExecJob(
                "id", "true", Path.of("o.log"), Path.of("e.log"));

        var rendered = ClientExecJobRenderer.render(job);

        assertThat(rendered).doesNotContainKeys("sessionId", "projectId");
    }
}
