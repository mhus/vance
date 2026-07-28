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
 * session:                   # optional mapping (see SessionSpec); absent = no session
 *   enabled: true            #   default true when the section is present
 *   name: my-agent           #   stable process identity (re-run continues it)
 *   recipe: arthur           #   makes the session process a conversational agent
 *   clean: false             #   true = reset the session process before the run
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
 *
 * @param session governs whether the REST run path provisions a session
 *                process (session + think-process) so {@code spawn} tasks and
 *                other process-scoped tooling have a process context. Disabled
 *                by default: the compose runs process-less — {@code exec}/
 *                {@code js}/{@code llm}, import and export all work, but a
 *                {@code spawn} task fails cleanly. Skipping the process avoids
 *                leaving an idle one that would be woken by {@code EXEC_FINISHED}
 *                events and burn LLM turns for nothing. Ignored when the run
 *                already binds to a real chat process (an active session's
 *                primary process is reused as-is). See {@link SessionSpec}.
 */
public record DamogranManifest(
        WorkspaceSpec workspace,
        List<ImportEntry> imports,
        List<TaskSpec> tasks,
        List<ExportEntry> exports,
        @Nullable String title,
        @Nullable String description,
        SessionSpec session,
        List<StateSpec> state) {

    /**
     * Back-compat constructor for a manifest without a {@code state:} section —
     * keeps existing callers/tests that build a manifest directly compiling.
     */
    public DamogranManifest(
            WorkspaceSpec workspace, List<ImportEntry> imports, List<TaskSpec> tasks,
            List<ExportEntry> exports, @Nullable String title, @Nullable String description,
            SessionSpec session) {
        this(workspace, imports, tasks, exports, title, description, session, List.of());
    }

    /**
     * The session process this compose binds to on the REST run path (the Web-UI
     * "Run" button / chatless surfaces). Written in YAML as a mapping under
     * {@code session:}; an absent section means no session process.
     *
     * @param enabled provision a session process at all — a present
     *                {@code session:} mapping defaults {@code enabled} to true
     *                unless {@code enabled: false} is set explicitly
     * @param name    stable process identity — re-running with the same name
     *                reuses the same process (memory continuity across runs). A
     *                {@code null} name falls back to a per-app / per-user scope.
     * @param recipe  when set, the session process is created as a conversational
     *                <em>agent</em> from this recipe (engine + prompt + tools);
     *                an {@code agent} task then delivers its prompt as a turn.
     *                {@code null} keeps the process a plain WORK-target holder
     *                (file/exec tools only, inert), the pre-agent behaviour.
     * @param clean   reset the session process before the run (drop its prior
     *                conversation) — a fresh start on an otherwise stable name
     */
    public record SessionSpec(
            boolean enabled, @Nullable String name, @Nullable String recipe, boolean clean) {

        /** No session process — the compose runs process-less. */
        public static final SessionSpec DISABLED = new SessionSpec(false, null, null, false);
    }

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
     * {@code secrets} maps an environment-variable name to a secret reference
     * ({@code vault:key}, {@code project:key}, …); the runner resolves each server
     * side and injects it as a sealed env var for {@code exec} tasks (WORK only),
     * keeping the value out of the manifest, the state store and the logs.
     */
    public record TaskSpec(
            String type,
            Map<String, Object> params,
            List<OutputSpec> declaredOutputs,
            Map<String, String> secrets) {

        public TaskSpec {
            secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        }

        /** Convenience without secrets — the common case and existing call sites. */
        public TaskSpec(String type, Map<String, Object> params, List<OutputSpec> declaredOutputs) {
            this(type, params, declaredOutputs, Map.of());
        }
    }

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

    /**
     * One entry of the top-level {@code state:} section — a management operation
     * applied (in list order) to the per-document, per-type state store
     * ({@code <workspace>/_damogran-state/<docKey>/<type>/}) before the tasks run.
     * State lets the code-executing tasks ({@code exec}/{@code python}/{@code js}/
     * {@code r}) carry a JSON-shaped {@code state} object between runs of the same
     * document, plus persisted {@code header}/{@code footer} code fragments the
     * handler wraps around the script. See {@code planning/damogran-state.md}.
     *
     * <p>Shapes (validated fail-fast by the parser):
     * <ul>
     *   <li>{@code {delete: true}} — wipe the whole {@code <docKey>/} store (all
     *       types); must stand alone (no {@code type}/{@code init}/{@code header}/
     *       {@code footer}).</li>
     *   <li>{@code {type: <t>, init: true}} — empty (recreate) that type's folder.</li>
     *   <li>{@code {type: <t>, header: <text>}} / {@code footer: <text>} — write
     *       that file into the type folder (creating it). {@code header}/{@code footer}
     *       may be empty strings.</li>
     * </ul>
     * {@code type} is required whenever {@code init}/{@code header}/{@code footer}
     * is set (they address a type folder); it is forbidden with {@code delete}.
     *
     * @param type   target state type (matches a task type: exec/python/js/r); may
     *               be {@code null} only for a {@code delete} entry
     * @param init   empty the type folder before any header/footer write
     * @param header persisted prolog code fragment, or {@code null} if not set
     *               (an empty string writes an empty header file)
     * @param footer persisted epilog code fragment, or {@code null} if not set
     * @param delete wipe the entire per-document store (all types)
     */
    public record StateSpec(
            @Nullable String type,
            boolean init,
            @Nullable String header,
            @Nullable String footer,
            boolean delete) {}
}
