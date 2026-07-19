package de.mhus.vance.brain.damogran;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parsed representation of a Damogran compose manifest — the declarative
 * definition that drives a compose run.
 *
 * <p>YAML shape:
 * <pre>{@code
 * workspace:
 *   name: my-workspace        # named, re-findable workspace (session-scoped)
 *   type: node                # temp | git | node | python | ephemeral | <addon>
 *   clear: false              # true = wipe before provisioning
 *   options:                  # type-specific
 *     url: https://…          # (git) repo
 *     modules: [lodash]       # (node) npm install on provisioning
 *   target: WORK              # WorkTarget: CLIENT | WORK | DAEMON (default WORK)
 *
 * import:
 *   - from: vance:main.tex    # vance: = document; http(s) = external source
 *     to: main.tex            # workspace-relative
 *   - from: http://example.com/data.txt
 *     to: data.txt
 *
 * tasks:
 *   - type: exec
 *     command: echo "Hello World"
 *   - type: llm
 *     recipe: analyze
 *     prompt: "Summarise data.txt"
 *     output: summary.md      # LLM reply lands as a workspace file
 *   - type: tex-task          # domain task provided by an addon bean
 *
 * export:
 *   - from: output.pdf        # workspace-relative
 *     to: vance:output.pdf    # document path
 * }</pre>
 *
 * <p>Task items carry a {@code type} discriminator resolved to a
 * {@link DamogranTask} bean; the remaining fields are task-type specific and
 * are handed to the bean as {@link TaskSpec#params()}.
 */
public record DamogranManifest(
        WorkspaceSpec workspace,
        List<ImportEntry> imports,
        List<TaskSpec> tasks,
        List<ExportEntry> exports,
        @Nullable String title,
        @Nullable String description) {

    /**
     * The workspace this compose operates on.
     *
     * @param name    re-findable, session-scoped workspace name (required)
     * @param type    provisioning recipe (temp/git/node/python/ephemeral/addon);
     *                a provisioning recipe, <em>not</em> a language lock
     * @param clear   wipe the workspace before provisioning
     * @param delete  terminal: dispose the named workspace and stop — no
     *                provisioning, no import/tasks/export (must be empty)
     * @param options type-specific provisioning options (git url, node modules…)
     * @param target  WorkTarget kind — {@code CLIENT}, {@code WORK} or
     *                {@code DAEMON} (default {@code WORK})
     */
    public record WorkspaceSpec(
            String name,
            String type,
            boolean clear,
            boolean delete,
            Map<String, Object> options,
            String target) {

        public static final String DEFAULT_TYPE = "temp";
        public static final String DEFAULT_TARGET = "WORK";
    }

    /**
     * An import step: pull content into the workspace before tasks run.
     *
     * @param from source URI — {@code vance:<path>} for a document, or an
     *             {@code http(s)://…} URL for an external resource
     * @param to   workspace-relative destination path
     */
    public record ImportEntry(String from, String to, Map<String, Object> options) {
        public @Nullable String option(String key) {
            return DamogranManifest.stringOption(options, key);
        }
    }

    /**
     * An export step: push a workspace file back out after tasks complete. The
     * source ({@code from}) is <em>always</em> workspace-local; {@code to}
     * carries the target scheme (e.g. {@code vance:<path>} document, or
     * {@code git:<url>} for commit/push). {@code options} holds scheme-specific
     * fields (branch, message, push, credentialAlias, …).
     *
     * @param from    workspace-relative source path
     * @param to      destination URI ({@code vance:<path>} / {@code git:<url>})
     * @param options scheme-specific extra fields
     */
    public record ExportEntry(String from, String to, Map<String, Object> options) {
        public @Nullable String option(String key) {
            return DamogranManifest.stringOption(options, key);
        }

        public boolean boolOption(String key, boolean fallback) {
            Object raw = options.get(key);
            return raw instanceof Boolean b ? b : fallback;
        }
    }

    private static @Nullable String stringOption(Map<String, Object> options, String key) {
        Object raw = options.get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    /**
     * A single task. {@code type} selects the {@link DamogranTask} bean;
     * {@code params} carries every other field verbatim for that bean to read.
     * {@code declaredOutputs} are the workspace files the task should surface as
     * outputs (for the notebook output region and as export candidates).
     */
    public record TaskSpec(
            String type,
            Map<String, Object> params,
            List<OutputSpec> declaredOutputs) {}

    /**
     * A declared output of a task: which workspace file to surface, with an
     * optional kind override (e.g. render {@code data.csv} as {@code records}
     * rather than raw text) and a display title. Absent {@code kind} means the
     * renderer auto-detects from extension / mime.
     *
     * @param path  workspace-relative path of the output file
     * @param kind  optional kind override for rendering
     * @param title optional display title
     */
    public record OutputSpec(String path, @Nullable String kind, @Nullable String title) {}
}
