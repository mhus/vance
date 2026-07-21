package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.Nullable;

/**
 * A concrete output produced by a task, resolved for rendering. Unlike
 * {@link DamogranManifest.OutputSpec} (the <em>declaration</em>), this is the
 * runtime artifact: a real workspace file with a resolved kind/mime.
 *
 * <p>Content is <em>not</em> inlined here. A workspace-file artifact stays in
 * the (transient) workspace and is loaded on demand via the workspace REST
 * surface ({@code GET /workspace/file}) addressed by a {@code vance-workspace:}
 * URI (the default when {@code uri} is {@code null}). This is the notebook
 * output-region model: outputs are workspace-sourced and flushed on pod/project
 * unload; persistent results go through an export step.
 *
 * <p>A non-workspace artifact carries an explicit {@code uri} — e.g. an
 * {@code agent} task references its session process's conversation with a
 * {@code vance-process:<id>} URI, which the client resolves against the process
 * message REST surface rather than the workspace.
 *
 * @param path  workspace-relative path of the file (or a bare identifier for
 *              non-workspace artifacts, e.g. the process id)
 * @param kind  resolved render kind (e.g. {@code image}, {@code records},
 *              {@code markdown}, {@code process}); {@code null} lets the client
 *              auto-detect
 * @param mime  resolved mime type, if known
 * @param title optional display title
 * @param uri   explicit resource URI; {@code null} means a workspace file
 *              (client builds the {@code vance-workspace:} URI from the path)
 */
public record OutputArtifact(
        String path,
        @Nullable String kind,
        @Nullable String mime,
        @Nullable String title,
        @Nullable String uri) {

    /** Workspace-file artifact — the client builds the {@code vance-workspace:} URI from the path. */
    public OutputArtifact(String path, @Nullable String kind, @Nullable String mime, @Nullable String title) {
        this(path, kind, mime, title, null);
    }

    public static OutputArtifact of(String path) {
        return new OutputArtifact(path, null, null, null, null);
    }

    /**
     * Reference to a session process's conversation — rendered as the agent's
     * latest answer (loaded from the process message REST surface).
     */
    public static OutputArtifact process(String processId) {
        return new OutputArtifact(processId, "process", null, "Agent", "vance-process:" + processId);
    }

    /**
     * Reference to a <em>specific</em> answer message of a session process —
     * {@code vance-process:<processId>/<messageId>}. Pins the exact turn's reply
     * (the process is reused across runs, so a bare process ref would drift to a
     * later answer); the client renders that message and shows its id.
     */
    public static OutputArtifact process(String processId, String messageId) {
        return new OutputArtifact(processId, "process", null, "Agent",
                "vance-process:" + processId + "/" + messageId);
    }
}
