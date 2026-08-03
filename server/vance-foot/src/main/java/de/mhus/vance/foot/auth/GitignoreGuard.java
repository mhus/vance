package de.mhus.vance.foot.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Keeps the secret {@code access.yaml} out of version control. After a
 * {@code /login} writes credentials into a project-local {@code .vance}
 * directory, this checks whether the enclosing git repository ignores the
 * file and, if not, appends {@code .vancetope/access.yaml} to the repo-root
 * {@code .gitignore}.
 *
 * <p>Deliberately narrow: it protects the credential file, not the whole
 * {@code .vancetope/} directory, so an intentionally-shared {@code project.eddie.yaml}
 * binding stays committable (the two-file split). It scans the repo-root
 * {@code .gitignore} for the common forms — a global excludes file or a
 * nested ignore is not consulted (a re-add is harmless: git already ignores
 * it, so the extra line is a no-op).
 */
@Component
@Slf4j
public class GitignoreGuard {

    /** The entry appended when the credential file is not yet ignored. */
    public static final String ACCESS_ENTRY = VancePaths.DIR_NAME + "/" + VancePaths.ACCESS_FILE;

    public enum Kind {
        /** No enclosing git repository — nothing to do. */
        NO_GIT,
        /** The credential file was already ignored. */
        ALREADY_IGNORED,
        /** An entry was appended to {@code .gitignore}. */
        ADDED
    }

    /** Outcome of {@link #ensureAccessIgnored(Path)}. */
    public record Result(Kind kind, @Nullable Path gitignore, @Nullable String entry) {
    }

    /**
     * Ensures the {@code access.yaml} inside {@code vanceDir} is git-ignored.
     *
     * @param vanceDir the {@code .vance} directory the credentials were written to
     */
    public Result ensureAccessIgnored(Path vanceDir) {
        Path start = vanceDir.toAbsolutePath().normalize();
        Path repoRoot = findGitRoot(start.getParent());
        if (repoRoot == null) {
            return new Result(Kind.NO_GIT, null, null);
        }

        // Path of the credential file relative to the repo root, in POSIX form.
        Path accessAbs = start.resolve(VancePaths.ACCESS_FILE);
        String relative = toPosix(repoRoot.relativize(accessAbs));

        Path gitignore = repoRoot.resolve(".gitignore");
        if (isIgnored(gitignore, relative)) {
            return new Result(Kind.ALREADY_IGNORED, gitignore, null);
        }
        String entry = chooseEntry(relative);
        append(gitignore, entry);
        log.info("added '{}' to {}", entry, gitignore);
        return new Result(Kind.ADDED, gitignore, entry);
    }

    /** Walks up from {@code dir} until a directory containing {@code .git} is found. */
    private static @Nullable Path findGitRoot(@Nullable Path dir) {
        Path cur = dir;
        while (cur != null) {
            if (Files.exists(cur.resolve(".git"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * Whether {@code .gitignore} already covers {@code relative} (POSIX path
     * relative to the repo root, e.g. {@code .vancetope/access.yaml}). Matches the
     * common line forms: the file itself, the containing {@code .vance}
     * directory (bare or trailing-slash), and root-anchored variants.
     */
    private boolean isIgnored(Path gitignore, String relative) {
        if (!Files.isRegularFile(gitignore)) {
            return false;
        }
        List<String> patterns = coveringPatterns(relative);
        try {
            for (String raw : Files.readAllLines(gitignore)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (patterns.contains(line)) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.debug("could not read {} (treating as not-ignored): {}", gitignore, e.getMessage());
            return false;
        }
        return false;
    }

    /** The set of {@code .gitignore} lines that would cover {@code relative}. */
    private static List<String> coveringPatterns(String relative) {
        // relative is e.g. ".vancetope/access.yaml" or "sub/.vancetope/access.yaml".
        int slash = relative.lastIndexOf('/');
        String dir = slash < 0 ? "" : relative.substring(0, slash); // ".vancetope" or "sub/.vancetope"
        return List.of(
                relative,            // .vancetope/access.yaml
                "/" + relative,      // /.vancetope/access.yaml
                dir,                 // .vance
                dir + "/",           // .vancetope/
                "/" + dir,           // /.vancetope
                "/" + dir + "/",     // /.vancetope/
                "**/" + dir + "/");  // **/.vancetope/
    }

    /**
     * The line to append. When the file sits directly under the repo root
     * ({@code .vancetope/access.yaml}) we use that; a nested location keeps its
     * relative prefix so the pattern still resolves from the repo root.
     */
    private static String chooseEntry(String relative) {
        return relative;
    }

    private void append(Path gitignore, String entry) {
        try {
            StringBuilder sb = new StringBuilder();
            if (Files.isRegularFile(gitignore)) {
                String existing = Files.readString(gitignore);
                sb.append(existing);
                if (!existing.isEmpty() && !existing.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            sb.append(entry).append('\n');
            Files.writeString(gitignore, sb.toString());
        } catch (IOException e) {
            throw new AccessStoreException(
                    "Failed to update " + gitignore + ": " + e.getMessage(), e);
        }
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }
}
