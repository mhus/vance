package de.mhus.vance.shared.workspace;

import de.mhus.vance.exec.GitOperationException;
import de.mhus.vance.exec.GitOperations;
import java.io.IOException;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@link GitOperations} on JGit, with credentials from the {@link GitAuthProvider}.
 * The runner has its own implementation on the {@code git} program.
 */
@Component
@RequiredArgsConstructor
public class JGitOperations implements GitOperations {

    private final GitAuthProvider authProvider;

    @Override
    public void init(Path dir) {
        try {
            Git.init().setDirectory(dir.toFile()).call().close();
        } catch (GitAPIException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public void clone(String repoUrl, Path dir, String branch, @Nullable Integer depth, Scope scope) {
        CloneCommand clone = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(dir.toFile())
                .setBranch(branch)
                .setCredentialsProvider(credentials(scope));
        // CloneCommand.setDepth(int) is primitive — an absent depth must
        // skip the call, not unbox null into an NPE.
        if (depth != null) {
            clone.setDepth(depth);
        }
        try (Git ignored = clone.call()) {
            // the clone is on disk; the handle is only closed
        } catch (GitAPIException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public void checkout(Path repo, String ref) {
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setName(ref).call();
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public @Nullable String head(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? null : head.getName();
        } catch (IOException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isClean(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            return git.status().call().isClean();
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public void checkoutNewBranch(Path repo, String branch) {
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setCreateBranch(true).setName(branch).call();
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public void commitAll(Path repo, String message) {
        try (Git git = Git.open(repo.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setAllowEmpty(false).setMessage(message).call();
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public void push(Path repo, String remote, String branch, Scope scope) {
        try (Git git = Git.open(repo.toFile())) {
            git.push()
                    .setRemote(remote)
                    .add(branch)
                    .setCredentialsProvider(credentials(scope))
                    .call();
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    private @Nullable CredentialsProvider credentials(Scope scope) {
        return authProvider.provide(scope.tenantId(), scope.projectId(), scope.credentialAlias());
    }
}
