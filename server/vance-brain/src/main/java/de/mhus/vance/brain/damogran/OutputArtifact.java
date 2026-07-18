package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.Nullable;

/**
 * A concrete output produced by a task, resolved for rendering. Unlike
 * {@link DamogranManifest.OutputSpec} (the <em>declaration</em>), this is the
 * runtime artifact: a real workspace file with a resolved kind/mime.
 *
 * <p>Content is <em>not</em> inlined here — it stays in the (transient)
 * workspace and is loaded on demand via the workspace REST surface
 * ({@code GET /workspace/file}) addressed by a {@code vance-workspace:} URI.
 * This is the notebook output-region model: outputs are workspace-sourced and
 * flushed on pod/project unload; persistent results go through an export step.
 *
 * @param path  workspace-relative path of the file
 * @param kind  resolved render kind (e.g. {@code image}, {@code records},
 *              {@code markdown}); {@code null} lets the client auto-detect
 * @param mime  resolved mime type, if known
 * @param title optional display title
 */
public record OutputArtifact(
        String path,
        @Nullable String kind,
        @Nullable String mime,
        @Nullable String title) {

    public static OutputArtifact of(String path) {
        return new OutputArtifact(path, null, null, null);
    }
}
