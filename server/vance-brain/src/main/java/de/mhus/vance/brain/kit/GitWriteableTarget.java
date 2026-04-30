package de.mhus.vance.brain.kit;

import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git-backed {@link WriteableTarget}. Wraps a JGit clone; commits + pushes
 * on {@link #commitAndPublish(String, String)}.
 */
final class GitWriteableTarget implements WriteableTarget {

    private static final Logger log = LoggerFactory.getLogger(GitWriteableTarget.class);

    private final Git git;
    private final java.nio.file.Path workTree;
    private final @Nullable String token;

    GitWriteableTarget(Git git, java.nio.file.Path workTree, @Nullable String token) {
        this.git = git;
        this.workTree = workTree;
        this.token = token;
    }

    @Override
    public java.nio.file.Path workTree() {
        return workTree;
    }

    @Override
    public @Nullable String token() {
        return token;
    }

    @Override
    public Optional<String> commitAndPublish(String commitMessage, @Nullable String actor) {
        try {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            PersonIdent author = author(actor);
            String sha = git.commit()
                    .setMessage(commitMessage)
                    .setAuthor(author)
                    .setCommitter(author)
                    .setAllowEmpty(false)
                    .call()
                    .getName();
            git.push()
                    .setCredentialsProvider(credentials(token))
                    .call();
            log.info("kit-export: pushed commit {}", sha);
            return Optional.of(sha);
        } catch (GitAPIException e) {
            // EmptyCommitException is wrapped — surface as "nothing to do".
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("empty commit")) {
                log.info("kit-export: nothing to commit — repo is up to date");
                return Optional.empty();
            }
            throw new KitException("git commit/push failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        git.close();
    }

    private static PersonIdent author(@Nullable String actor) {
        String name = actor == null || actor.isBlank() ? "vance" : actor;
        String email = actor == null || actor.isBlank() ? "vance@localhost" : actor;
        if (!email.contains("@")) {
            email = email + "@vance";
        }
        return new PersonIdent(name, email);
    }

    private static @Nullable UsernamePasswordCredentialsProvider credentials(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(token, "");
    }
}
