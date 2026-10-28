package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * {@link GitOperations} for handler tests that only care that {@code git init}
 * happened: it creates the {@code .git} directory and refuses everything else.
 */
final class FakeGitOperations implements GitOperations {

    @Override
    public void init(Path dir) {
        try {
            Files.createDirectories(dir.resolve(".git"));
        } catch (IOException e) {
            throw new GitOperationException(e.getMessage(), e);
        }
    }

    @Override
    public void clone(String repoUrl, Path dir, String branch, @Nullable Integer depth, Scope scope) {
        throw new UnsupportedOperationException("clone");
    }

    @Override
    public void checkout(Path repo, String ref) {
        throw new UnsupportedOperationException("checkout");
    }

    @Override
    public @Nullable String head(Path repo) {
        throw new UnsupportedOperationException("head");
    }

    @Override
    public boolean isClean(Path repo) {
        throw new UnsupportedOperationException("isClean");
    }

    @Override
    public void checkoutNewBranch(Path repo, String branch) {
        throw new UnsupportedOperationException("checkoutNewBranch");
    }

    @Override
    public void commitAll(Path repo, String message) {
        throw new UnsupportedOperationException("commitAll");
    }

    @Override
    public void push(Path repo, String remote, String branch, Scope scope) {
        throw new UnsupportedOperationException("push");
    }
}
