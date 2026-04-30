package de.mhus.vance.brain.kit;

import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Strategy for an export destination. Two production implementations:
 *
 * <ul>
 *   <li><b>Git</b> ({@code https://}, {@code git@}, {@code ssh://}) — clones
 *       the remote into a workspace temp dir, exposes the work-tree for
 *       writes, then commits + pushes on
 *       {@link #commitAndPublish(String, String)}.</li>
 *   <li><b>Folder</b> ({@code file://}, absolute path) — writes directly
 *       into the target directory, no git involved. Suitable for
 *       snapshot-to-disk, NAS, USB-stick, and ai-test scenarios where
 *       spinning up a real git remote would be overkill.</li>
 * </ul>
 *
 * <p>{@link KitExporter} drives the writer: it asks for a {@link #workTree()}
 * to populate, then calls {@link #commitAndPublish(String, String)} once
 * the kit tree has been written. The folder impl returns
 * {@link Optional#empty()} from publish (no commit), the git impl returns
 * the new commit SHA.
 */
public interface WriteableTarget extends AutoCloseable {

    /** Directory where the kit tree (kit.yaml + documents/ + …) is written. */
    Path workTree();

    /**
     * Persist the writes performed under {@link #workTree()}.
     *
     * <ul>
     *   <li>Git impl: {@code git add . && git commit -m message && git push}.
     *       Returns the new commit SHA, or {@link Optional#empty()} if there
     *       was nothing to commit (repo already in sync).</li>
     *   <li>Folder impl: no-op. Files are already on disk. Returns
     *       {@link Optional#empty()}.</li>
     * </ul>
     *
     * @param commitMessage human-readable message; ignored by folder impl
     * @param actor         actor identity for the commit author / committer;
     *                      may be {@code null}
     */
    Optional<String> commitAndPublish(String commitMessage, @Nullable String actor);

    /** Optional credentials token for git pushes; {@code null} for folder. */
    @Nullable String token();

    /** Releases resources (closes JGit handles for the git impl). */
    @Override
    void close();
}
