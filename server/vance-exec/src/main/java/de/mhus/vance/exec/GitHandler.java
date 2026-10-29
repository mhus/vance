package de.mhus.vance.exec;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Git-backed RootDir — clones a repository on init, commits and pushes
 * a suspend-branch on suspend, re-clones on recover. The git calls themselves go
 * through {@link GitOperations} (JGit in the brain, the {@code git} program in the
 * runner); this class owns what is the same for both: the metadata keys, the
 * suspend branch, the depth and commit rules. The descriptor stores
 * {@code public} flag (never a token) so suspended descriptors carry no secrets.
 *
 * <p>Metadata schema:
 * <ul>
 *   <li>{@code repoUrl} (required) — clone URL</li>
 *   <li>{@code branch} — branch to check out, default {@code main}</li>
 *   <li>{@code checkoutCommit} — commit SHA to detach to after cloning;
 *       takes precedence over the branch tip. Set by {@code git_checkout}
 *       when the caller wants an exact source match (e.g. the commit the
 *       running brain was built from) instead of "whatever main is today"</li>
 *   <li>{@code commit} — populated by the handler with HEAD after init</li>
 *   <li>{@code public} — {@code true}: access the repository anonymously; otherwise the credentials
 *       {@link GitAuthProvider} holds for the repository's host are used</li>
 *   <li>{@code suspendBranch} — set during suspend; recover prefers it over {@code branch}</li>
 *   <li>{@code suspendCommit} — set during suspend</li>
 * </ul>
 */
@Slf4j
public class GitHandler implements WorkspaceContentHandler {

    public static final String TYPE = "git";

    public static final String META_REPO_URL = "repoUrl";
    public static final String META_BRANCH = "branch";
    public static final String META_CHECKOUT_COMMIT = "checkoutCommit";
    public static final String META_DEPTH = "depth";
    public static final String META_COMMIT = "commit";
    public static final String META_PUBLIC = "public";
    public static final String META_SUSPEND_BRANCH = "suspendBranch";
    public static final String META_SUSPEND_COMMIT = "suspendCommit";

    private static final String DEFAULT_BRANCH = "main";
    private static final String SUSPEND_BRANCH_PREFIX = "vance/suspend/";

    private final GitOperations git;

    public GitHandler(GitOperations git) {
        this.git = git;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(RootDirHandle handle, RootDirSpec spec) {
        Map<String, Object> meta = HandlerSupport.mutableMetadata(handle.getDescriptor());
        String repoUrl = HandlerSupport.stringOrThrow(meta, META_REPO_URL);
        String branch = HandlerSupport.stringOr(meta, META_BRANCH, DEFAULT_BRANCH);
        GitOperations.Scope scope =
                scope(handle.getDescriptor().getTenant(), handle.getDescriptor().getProjectId(), meta);
        try {
            git.clone(repoUrl, handle.getPath(), branch, shallowDepth(meta), scope);
            checkoutCommitIfRequested(handle.getPath(), meta);
            String head = git.head(handle.getPath());
            meta.put(META_COMMIT, head == null ? "unknown" : head);
            handle.getDescriptor().setMetadata(meta);
            log.info(
                    "git init: {} → {} (branch={} commit={})",
                    repoUrl,
                    handle.getDirName(),
                    branch,
                    meta.get(META_COMMIT));
        } catch (GitOperationException e) {
            throw new RootDirException("git clone failed for " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Depth of a shallow clone, or {@code null} for full history. Only
     * positive integers count — anything else (absent, "", "soon") means
     * full clone, so a malformed value cannot break the init.
     */
    private static @Nullable Integer shallowDepth(Map<String, Object> meta) {
        Object raw = meta.get(META_DEPTH);
        if (raw instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                int depth = Integer.parseInt(text.trim());
                if (depth > 0) {
                    return depth;
                }
            } catch (NumberFormatException ignored) {
                // falls through to full clone
            }
        }
        return null;
    }

    /**
     * Detach to {@code checkoutCommit} when the metadata names one. A commit that
     * does not exist in the clone fails the whole init — a RootDir that silently
     * sits on the wrong revision is worse than no RootDir.
     */
    private void checkoutCommitIfRequested(java.nio.file.Path repo, Map<String, Object> meta) {
        // stringOr() normalizes an absent/null value to "" — blank is the
        // "not requested" marker here, not a ref name to attempt.
        String checkoutCommit = HandlerSupport.stringOr(meta, META_CHECKOUT_COMMIT, null);
        if (checkoutCommit.isBlank()) {
            return;
        }
        try {
            git.checkout(repo, checkoutCommit);
        } catch (GitOperationException e) {
            throw new GitOperationException("checkout of commit '" + checkoutCommit + "' failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void suspend(RootDirHandle handle) {
        Map<String, Object> meta = HandlerSupport.mutableMetadata(handle.getDescriptor());
        GitOperations.Scope scope =
                scope(handle.getDescriptor().getTenant(), handle.getDescriptor().getProjectId(), meta);
        String suspendBranch = SUSPEND_BRANCH_PREFIX + handle.getDirName();
        java.nio.file.Path repo = handle.getPath();
        try {
            boolean dirty = !git.isClean(repo);
            git.checkoutNewBranch(repo, suspendBranch);
            if (dirty) {
                git.commitAll(repo, "vance suspend");
            }
            git.push(repo, "origin", suspendBranch, scope);
            String head = git.head(repo);
            meta.put(META_SUSPEND_BRANCH, suspendBranch);
            meta.put(META_SUSPEND_COMMIT, head == null ? "unknown" : head);
            handle.getDescriptor().setMetadata(meta);
            log.info(
                    "git suspend: {} → {} (commit={})",
                    handle.getDirName(),
                    suspendBranch,
                    meta.get(META_SUSPEND_COMMIT));
        } catch (GitOperationException e) {
            throw new RootDirException("git suspend failed for " + handle.getDirName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void recover(RootDirHandle handle, WorkspaceDescriptor descriptor) {
        Map<String, Object> meta = HandlerSupport.mutableMetadata(descriptor);
        String repoUrl = HandlerSupport.stringOrThrow(meta, META_REPO_URL);
        String suspendBranch = HandlerSupport.stringOr(meta, META_SUSPEND_BRANCH, null);
        String branch =
                !suspendBranch.isBlank() ? suspendBranch : HandlerSupport.stringOr(meta, META_BRANCH, DEFAULT_BRANCH);
        GitOperations.Scope scope = scope(descriptor.getTenant(), descriptor.getProjectId(), meta);
        try {
            git.clone(repoUrl, handle.getPath(), branch, shallowDepth(meta), scope);
            checkoutCommitIfRequested(handle.getPath(), meta);
            log.info("git recover: {} → {} (branch={})", repoUrl, handle.getDirName(), branch);
        } catch (GitOperationException e) {
            throw new RootDirException("git recover failed for " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close(RootDirHandle handle) {
        // Folder removal is the store's job. No commit-on-close in V1 —
        // workers explicitly call suspend if they need persistence.
    }

    /** Credential context for remote calls — whether to go anonymous, never a token. */
    private static GitOperations.Scope scope(
            @Nullable String tenantId, @Nullable String projectId, Map<String, Object> meta) {
        Object raw = meta.get(META_PUBLIC);
        boolean publicAccess =
                raw instanceof Boolean b ? b : raw instanceof String s && Boolean.parseBoolean(s.strip());
        return new GitOperations.Scope(tenantId, projectId, publicAccess);
    }
}
