package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.workspace.GitAuthProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Git as a cross-cutting <em>aspect</em> — plain clone / pull / commit+push
 * operations on a given filesystem directory, using the shared
 * {@link GitAuthProvider} for credentials. Deliberately <em>not</em> a
 * workspace type ({@code GitHandler} keeps that role): a node/python workspace
 * can carry a git-imported folder without being "a git workspace".
 *
 * <p>Used by {@link GitImporter} (remote → workspace dir) and
 * {@link GitExporter} (workspace dir → remote).
 */
@Service
public class GitService {

    private final GitAuthProvider authProvider;

    public GitService(GitAuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    /** Clone {@code url} into {@code dir}, or pull if {@code dir} is already a repo. */
    public void cloneOrPull(Path dir, String url, @Nullable String branch,
                            String tenantId, @Nullable String projectId, @Nullable String alias) {
        CredentialsProvider creds = authProvider.provide(tenantId, projectId, alias);
        if (isGitRepo(dir)) {
            try (Git git = Git.open(dir.toFile())) {
                git.pull().setCredentialsProvider(creds).call();
            } catch (IOException | GitAPIException e) {
                throw new DamogranException("git pull failed for " + url + ": " + e.getMessage(), e);
            }
            return;
        }
        try {
            Files.createDirectories(dir);
            var clone = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(dir.toFile())
                    .setCredentialsProvider(creds);
            if (StringUtils.isNotBlank(branch)) {
                clone.setBranch(branch);
            }
            clone.call().close();
        } catch (GitAPIException | IOException e) {
            throw new DamogranException("git clone failed for " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Stage all changes in {@code dir}, commit (if dirty), and — when
     * {@code push} — push {@code HEAD} to {@code branch} on {@code url}.
     */
    public void commitAndPush(Path dir, String url, @Nullable String branch, String message,
                              boolean push, String tenantId, @Nullable String projectId,
                              @Nullable String alias) {
        if (!isGitRepo(dir)) {
            throw new DamogranException("git export source '" + dir.getFileName()
                    + "' is not a git working tree (clone it first via a git import)");
        }
        CredentialsProvider creds = authProvider.provide(tenantId, projectId, alias);
        try (Git git = Git.open(dir.toFile())) {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call(); // stage deletions too
            if (!git.status().call().isClean()) {
                git.commit().setMessage(message).call();
            }
            if (push) {
                String target = StringUtils.isNotBlank(branch) ? branch : git.getRepository().getBranch();
                git.push()
                        .setRemote(url)
                        .setRefSpecs(new RefSpec("HEAD:refs/heads/" + target))
                        .setCredentialsProvider(creds)
                        .call();
            }
        } catch (IOException | GitAPIException e) {
            throw new DamogranException("git commit/push failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private static boolean isGitRepo(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }
}
