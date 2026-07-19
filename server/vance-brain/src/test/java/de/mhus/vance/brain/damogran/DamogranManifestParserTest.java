package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import org.junit.jupiter.api.Test;

class DamogranManifestParserTest {

    private final DamogranManifestParser parser = new DamogranManifestParser();

    @Test
    void parse_fullManifest_mapsAllSections() {
        String yaml =
                """
                workspace:
                  name: my-workspace
                  type: node
                  clear: true
                  options:
                    modules: [lodash, dayjs]
                  target: work
                import:
                  - from: vance:main.tex
                    to: main.tex
                  - from: http://example.com/data.txt
                    to: data.txt
                tasks:
                  - type: exec
                    command: echo "Hello World"
                  - type: llm
                    recipe: analyze
                    prompt: Summarise data.txt
                    output: summary.md
                export:
                  - from: output.pdf
                    to: vance:output.pdf
                """;

        DamogranManifest m = parser.parse(yaml);

        assertThat(m.workspace().name()).isEqualTo("my-workspace");
        assertThat(m.workspace().type()).isEqualTo("node");
        assertThat(m.workspace().clear()).isTrue();
        assertThat(m.workspace().target()).isEqualTo("WORK"); // uppercased
        assertThat(m.workspace().options()).containsKey("modules");

        assertThat(m.imports()).hasSize(2);
        assertThat(m.imports().get(0).from()).isEqualTo("vance:main.tex");
        assertThat(m.imports().get(0).to()).isEqualTo("main.tex");

        assertThat(m.tasks()).hasSize(2);
        TaskSpec exec = m.tasks().get(0);
        assertThat(exec.type()).isEqualTo("exec");
        assertThat(exec.params()).containsEntry("command", "echo \"Hello World\"");
        assertThat(exec.params()).doesNotContainKey("type");

        TaskSpec llm = m.tasks().get(1);
        assertThat(llm.type()).isEqualTo("llm");
        assertThat(llm.declaredOutputs()).hasSize(1);
        assertThat(llm.declaredOutputs().get(0).path()).isEqualTo("summary.md");

        assertThat(m.exports()).hasSize(1);
        assertThat(m.exports().get(0).from()).isEqualTo("output.pdf");
        assertThat(m.exports().get(0).to()).isEqualTo("vance:output.pdf");
    }

    @Test
    void parse_minimalManifest_appliesDefaults() {
        String yaml =
                """
                workspace:
                  name: scratch
                """;

        DamogranManifest m = parser.parse(yaml);

        assertThat(m.workspace().name()).isEqualTo("scratch");
        assertThat(m.workspace().type()).isEqualTo("temp");
        assertThat(m.workspace().target()).isEqualTo("WORK");
        assertThat(m.workspace().clear()).isFalse();
        assertThat(m.workspace().options()).isEmpty();
        assertThat(m.imports()).isEmpty();
        assertThat(m.tasks()).isEmpty();
        assertThat(m.exports()).isEmpty();
    }

    @Test
    void parse_outputsListWithKindOverride_isParsed() {
        String yaml =
                """
                workspace:
                  name: scratch
                tasks:
                  - type: python
                    script: build.py
                    outputs:
                      - report.md
                      - path: data.csv
                        as: records
                        title: Result Table
                """;

        TaskSpec task = parser.parse(yaml).tasks().get(0);

        assertThat(task.declaredOutputs()).hasSize(2);
        assertThat(task.declaredOutputs().get(0).path()).isEqualTo("report.md");
        assertThat(task.declaredOutputs().get(0).kind()).isNull();
        assertThat(task.declaredOutputs().get(1).path()).isEqualTo("data.csv");
        assertThat(task.declaredOutputs().get(1).kind()).isEqualTo("records");
        assertThat(task.declaredOutputs().get(1).title()).isEqualTo("Result Table");
        assertThat(task.params()).doesNotContainKey("outputs");
    }

    @Test
    void parse_emptyYaml_throws() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void parse_missingWorkspace_throws() {
        assertThatThrownBy(() -> parser.parse("tasks:\n  - type: exec\n"))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("workspace");
    }

    @Test
    void parse_workspaceWithoutName_throws() {
        assertThatThrownBy(() -> parser.parse("workspace:\n  type: git\n"))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("workspace.name");
    }

    @Test
    void parse_invalidTarget_throws() {
        assertThatThrownBy(() -> parser.parse("workspace:\n  name: x\n  target: bogus\n"))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("target");
    }

    @Test
    void parse_taskWithoutType_throws() {
        assertThatThrownBy(() -> parser.parse("workspace:\n  name: x\ntasks:\n  - command: ls\n"))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("type");
    }

    @Test
    void parse_importEntryMissingTo_throws() {
        String yaml =
                """
                workspace:
                  name: x
                import:
                  - from: vance:a.txt
                """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("'to'");
    }

    @Test
    void parse_importExportExtraKeys_capturedAsOptions() {
        String yaml =
                """
                workspace:
                  name: paper
                import:
                  - from: git:https://example.com/repo.git
                    to: repo
                    branch: main
                    credentialAlias: gh
                export:
                  - from: repo
                    to: git:https://example.com/repo.git
                    message: Update from Damogran
                    push: true
                """;

        DamogranManifest m = parser.parse(yaml);

        DamogranManifest.ImportEntry imp = m.imports().get(0);
        assertThat(imp.from()).isEqualTo("git:https://example.com/repo.git");
        assertThat(imp.to()).isEqualTo("repo");
        assertThat(imp.option("branch")).isEqualTo("main");
        assertThat(imp.option("credentialAlias")).isEqualTo("gh");

        DamogranManifest.ExportEntry exp = m.exports().get(0);
        assertThat(exp.option("message")).isEqualTo("Update from Damogran");
        assertThat(exp.boolOption("push", false)).isTrue();
    }
}
