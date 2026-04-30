package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Materializes a kit reference into a local directory tree — either by
 * cloning a git repo via JGit or by copying a local folder.
 *
 * <p>The loader does not own its target directory. Callers (typically
 * {@link KitResolver}) allocate one via {@link KitWorkspace} and pass
 * it in. The loader populates the directory and parses
 * {@code kit.yaml} at the configured sub-{@code path}.
 *
 * <p>Authentication for HTTPS repos uses GitHub-style token auth:
 * username {@code x-access-token}, password = the token. SSH URLs are
 * not supported in v1.
 */
@Service
@Slf4j
public class KitRepoLoader {

    /**
     * Read-only handle on a materialized kit. {@link #root} is the
     * directory containing {@code kit.yaml} (i.e. {@code repoRoot} +
     * the optional sub-{@code path}).
     */
    public record LoadedKit(
            Path root,
            Path repoRoot,
            String commit,
            KitDescriptorDto descriptor,
            boolean fromFolder) {}

    /**
     * Mutable handle on a clone opened for writing — used by export.
     * The {@link Git} instance must be {@link Git#close() closed} when
     * done.
     */
    public static final class WriteableClone implements AutoCloseable {
        private final Git git;
        private final Path repoRoot;
        private final Path workTree;
        private final @Nullable String token;

        WriteableClone(Git git, Path repoRoot, Path workTree, @Nullable String token) {
            this.git = git;
            this.repoRoot = repoRoot;
            this.workTree = workTree;
            this.token = token;
        }

        public Git git() { return git; }
        public Path repoRoot() { return repoRoot; }
        public Path workTree() { return workTree; }
        public @Nullable String token() { return token; }

        @Override
        public void close() {
            git.close();
        }
    }

    /**
     * Load a kit into {@code target}. Returns the parsed descriptor
     * and the commit SHA. {@code target} is expected to be an empty
     * directory (allocated via {@link KitWorkspace#allocate}).
     */
    public LoadedKit load(KitInheritDto source, @Nullable String token, Path target) {
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            throw new KitException("kit source url must not be blank");
        }
        boolean fromFolder = isFolderUrl(source.getUrl());
        String commit;
        Path repoRoot;
        if (fromFolder) {
            Path folder = resolveFolderUrl(source.getUrl());
            if (!Files.isDirectory(folder)) {
                throw new KitException("folder url does not point to a directory: " + folder);
            }
            copyTree(folder, target);
            repoRoot = target;
            commit = "folder:" + folder;
        } else {
            commit = cloneRepo(source, token, target);
            repoRoot = target;
        }

        Path root = subPath(repoRoot, source.getPath());
        Path descriptorFile = root.resolve("kit.yaml");
        if (!Files.isRegularFile(descriptorFile)) {
            throw new KitException("missing kit.yaml at " + descriptorFile);
        }
        KitDescriptorDto descriptor;
        try {
            descriptor = KitYamlMapper.parseDescriptor(Files.readString(descriptorFile));
        } catch (IOException e) {
            throw new KitException("failed to read " + descriptorFile, e);
        }
        log.info("Loaded kit '{}' from {} (commit {})", descriptor.getName(), source.getUrl(), commit);
        return new LoadedKit(root, repoRoot, commit, descriptor, fromFolder);
    }

    /**
     * Open a writable clone for export. The branch is checked out into
     * a fresh working tree at {@code target}; commit + push happens
     * via the returned {@link WriteableClone}.
     */
    public WriteableClone openForWrite(
            String url, @Nullable String branch, @Nullable String token, Path target) {
        if (url == null || url.isBlank()) {
            throw new KitException("export url must not be blank");
        }
        if (isFolderUrl(url)) {
            throw new KitException("folder urls cannot be used for export — use an https remote");
        }
        try {
            Git git = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(target.toFile())
                    .setBranch(branch == null || branch.isBlank() ? "main" : branch)
                    .setCredentialsProvider(credentials(token))
                    .call();
            return new WriteableClone(git, target, target, token);
        } catch (GitAPIException e) {
            throw new KitException("git clone failed for " + url + ": " + e.getMessage(), e);
        }
    }

    // ──────────────────── private ────────────────────

    private String cloneRepo(KitInheritDto source, @Nullable String token, Path target) {
        String branch = source.getBranch() == null || source.getBranch().isBlank()
                ? "main" : source.getBranch();
        try (Git git = Git.cloneRepository()
                .setURI(source.getUrl())
                .setDirectory(target.toFile())
                .setBranch(branch)
                .setCredentialsProvider(credentials(token))
                .call()) {
            if (source.getCommit() != null && !source.getCommit().isBlank()) {
                git.checkout().setName(source.getCommit()).call();
            }
            ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? "unknown" : head.getName();
        } catch (GitAPIException | IOException e) {
            throw new KitException("git clone failed for " + source.getUrl() + ": " + e.getMessage(), e);
        }
    }

    private static @Nullable UsernamePasswordCredentialsProvider credentials(@Nullable String token) {
        if (token == null || token.isBlank()) return null;
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    private static Path subPath(Path repoRoot, @Nullable String path) {
        if (path == null || path.isBlank()) return repoRoot;
        Path resolved = repoRoot.resolve(path).normalize();
        if (!resolved.startsWith(repoRoot)) {
            throw new KitException("kit path escapes repo root: " + path);
        }
        return resolved;
    }

    static boolean isFolderUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (trimmed.startsWith("file:")) return true;
        if (trimmed.startsWith("/")) return true;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")
                || trimmed.startsWith("git@") || trimmed.startsWith("ssh://")) return false;
        // Bare relative paths are not supported — only absolute or file://.
        return false;
    }

    private static Path resolveFolderUrl(String url) {
        String trimmed = url.trim();
        if (trimmed.startsWith("file:")) {
            try {
                return Path.of(URI.create(trimmed));
            } catch (IllegalArgumentException e) {
                throw new KitException("invalid file url: " + trimmed, e);
            }
        }
        return Path.of(trimmed);
    }

    private static void copyTree(Path source, Path target) {
        try {
            Files.walk(source)
                    .sorted(Comparator.naturalOrder())
                    .forEach(src -> {
                        Path rel = source.relativize(src);
                        Path dst = target.resolve(rel.toString());
                        try {
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dst);
                            } else {
                                Path parent = dst.getParent();
                                if (parent != null) Files.createDirectories(parent);
                                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new KitException("failed to copy " + src + " → " + dst, e);
                        }
                    });
        } catch (IOException e) {
            throw new KitException("failed to walk " + source, e);
        }
    }
}
