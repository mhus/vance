package de.mhus.vance.shared.workspace;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Git-backed RootDir — clones a repository on init, commits and pushes
 * a suspend-branch on suspend, re-clones on recover. Authentication
 * goes through {@link GitAuthProvider}; the descriptor stores
 * {@code credentialAlias} (not the token) so suspended descriptors
 * carry no secrets.
 *
 * <p>Metadata schema:
 * <ul>
 *   <li>{@code repoUrl} (required) — clone URL</li>
 *   <li>{@code branch} — branch to check out, default {@code main}</li>
 *   <li>{@code commit} — populated by the handler with HEAD after init</li>
 *   <li>{@code credentialAlias} — alias resolved by {@link GitAuthProvider}</li>
 *   <li>{@code suspendBranch} — set during suspend; recover prefers it over {@code branch}</li>
 *   <li>{@code suspendCommit} — set during suspend</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHandler implements WorkspaceContentHandler {

    public static final String TYPE = "git";

    public static final String META_REPO_URL = "repoUrl";
    public static final String META_BRANCH = "branch";
    public static final String META_COMMIT = "commit";
    public static final String META_CREDENTIAL_ALIAS = "credentialAlias";
    public static final String META_SUSPEND_BRANCH = "suspendBranch";
    public static final String META_SUSPEND_COMMIT = "suspendCommit";

    private static final String DEFAULT_BRANCH = "main";
    private static final String SUSPEND_BRANCH_PREFIX = "vance/suspend/";

    private final GitAuthProvider authProvider;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(RootDirHandle handle, RootDirSpec spec) {
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        String repoUrl = stringOrThrow(meta, META_REPO_URL);
        String branch = stringOr(meta, META_BRANCH, DEFAULT_BRANCH);
        String alias = stringOr(meta, META_CREDENTIAL_ALIAS, null);
        CredentialsProvider creds = authProvider.provide(
                handle.getDescriptor().getTenant(),
                handle.getDescriptor().getProjectId(), alias);

        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(handle.getPath().toFile())
                .setBranch(branch)
                .setCredentialsProvider(creds)
                .call()) {
            ObjectId head = git.getRepository().resolve("HEAD");
            meta.put(META_COMMIT, head == null ? "unknown" : head.getName());
            handle.getDescriptor().setMetadata(meta);
            log.info("git init: {} → {} (branch={} commit={})",
                    repoUrl, handle.getDirName(), branch, meta.get(META_COMMIT));
        } catch (GitAPIException | IOException e) {
            throw new WorkspaceException(
                    "git clone failed for " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void suspend(RootDirHandle handle) {
        Map<String, Object> meta = mutableMetadata(handle.getDescriptor());
        String alias = stringOr(meta, META_CREDENTIAL_ALIAS, null);
        CredentialsProvider creds = authProvider.provide(
                handle.getDescriptor().getTenant(),
                handle.getDescriptor().getProjectId(), alias);
        String suspendBranch = SUSPEND_BRANCH_PREFIX + handle.getDirName();
        try (Git git = Git.open(handle.getPath().toFile())) {
            boolean dirty = !git.status().call().isClean();
            git.checkout().setCreateBranch(true).setName(suspendBranch).call();
            if (dirty) {
                git.add().addFilepattern(".").call();
                git.commit().setAllowEmpty(false).setMessage("vance suspend").call();
            }
            git.push()
                    .setRemote("origin")
                    .add(suspendBranch)
                    .setCredentialsProvider(creds)
                    .call();
            ObjectId head = git.getRepository().resolve("HEAD");
            meta.put(META_SUSPEND_BRANCH, suspendBranch);
            meta.put(META_SUSPEND_COMMIT, head == null ? "unknown" : head.getName());
            handle.getDescriptor().setMetadata(meta);
            log.info("git suspend: {} → {} (commit={})",
                    handle.getDirName(), suspendBranch, meta.get(META_SUSPEND_COMMIT));
        } catch (GitAPIException | IOException e) {
            throw new WorkspaceException(
                    "git suspend failed for " + handle.getDirName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void recover(RootDirHandle handle, WorkspaceDescriptor descriptor) {
        Map<String, Object> meta = mutableMetadata(descriptor);
        String repoUrl = stringOrThrow(meta, META_REPO_URL);
        String suspendBranch = stringOr(meta, META_SUSPEND_BRANCH, null);
        String branch = StringUtils.isNotBlank(suspendBranch)
                ? suspendBranch
                : stringOr(meta, META_BRANCH, DEFAULT_BRANCH);
        String alias = stringOr(meta, META_CREDENTIAL_ALIAS, null);
        CredentialsProvider creds = authProvider.provide(
                descriptor.getTenant(), descriptor.getProjectId(), alias);
        try (Git ignored = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(handle.getPath().toFile())
                .setBranch(branch)
                .setCredentialsProvider(creds)
                .call()) {
            log.info("git recover: {} → {} (branch={})", repoUrl, handle.getDirName(), branch);
        } catch (GitAPIException e) {
            throw new WorkspaceException(
                    "git recover failed for " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close(RootDirHandle handle) {
        // Folder removal is the service's job. No commit-on-close in V1 —
        // workers explicitly call suspend if they need persistence.
    }

    private static Map<String, Object> mutableMetadata(WorkspaceDescriptor d) {
        Map<String, Object> meta = d.getMetadata();
        if (meta == null) {
            meta = new LinkedHashMap<>();
        } else if (!(meta instanceof HashMap<?, ?>) && !(meta instanceof LinkedHashMap<?, ?>)) {
            meta = new LinkedHashMap<>(meta);
        }
        return meta;
    }

    private static String stringOrThrow(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new WorkspaceException("git metadata is missing required field '" + key + "'");
        }
        return s;
    }

    @SuppressWarnings("SameParameterValue")
    private static String stringOr(Map<String, Object> meta, String key, @Nullable String fallback) {
        Object v = meta.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback == null ? "" : fallback;
    }
}
