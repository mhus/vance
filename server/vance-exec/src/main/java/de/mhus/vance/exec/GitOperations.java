package de.mhus.vance.exec;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The few git operations the RootDir handlers need — the seam between the handlers'
 * logic (metadata keys, suspend branch, what a recover does) and the machinery that
 * runs git. The brain implements it with JGit; the runner with the {@code git}
 * program, which builds as a native image where JGit does not
 * ({@code planning/runner.md}).
 *
 * <p>Operations that talk to a remote take a {@link Scope} so the implementation can
 * look up credentials; nothing here ever carries a secret itself. Every failure is a
 * {@link GitOperationException}.
 */
public interface GitOperations {

    /**
     * Who is asking, for credential lookup.
     *
     * @param publicAccess {@code true} = go to the repository anonymously; otherwise the stored
     *     credentials for the repository's host are used when there are any
     */
    record Scope(@Nullable String tenantId, @Nullable String projectId, boolean publicAccess) {}

    /** {@code git init} in an existing directory. */
    void init(Path dir);

    /**
     * Clones {@code repoUrl} into the existing, empty {@code dir} and checks out
     * {@code branch}. {@code depth == null} = full history, otherwise a shallow clone
     * of that depth.
     */
    void clone(String repoUrl, Path dir, String branch, @Nullable Integer depth, Scope scope);

    /** Checks out a commit or ref (detaching for a commit). */
    void checkout(Path repo, String ref);

    /** The commit {@code HEAD} points at, or {@code null} when it cannot be resolved. */
    @Nullable
    String head(Path repo);

    /** {@code true} when the working tree has no uncommitted changes. */
    boolean isClean(Path repo);

    /** Creates {@code branch} and checks it out. */
    void checkoutNewBranch(Path repo, String branch);

    /** Stages everything and commits it; fails when there is nothing to commit. */
    void commitAll(Path repo, String message);

    /** Pushes {@code branch} to {@code remote}. */
    void push(Path repo, String remote, String branch, Scope scope);
}
