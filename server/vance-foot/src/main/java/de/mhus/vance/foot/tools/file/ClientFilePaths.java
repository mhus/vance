package de.mhus.vance.foot.tools.file;

import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Path-string normalisation for client file tools. Expands a leading
 * {@code "~"} or {@code "~/…"} to the user's home directory — the
 * shell would do this implicitly, but Java's {@code Path.of(...)}
 * treats {@code "~"} as a literal segment, so the LLM's intent gets
 * lost without a deliberate expansion here.
 */
public final class ClientFilePaths {

    private ClientFilePaths() {}

    public static Path resolve(String raw) {
        if (raw == null || raw.isEmpty()) return Path.of("");
        String expanded = expandHome(raw);
        return Path.of(expanded);
    }

    /**
     * Renders a path the way it must appear in tool <em>output</em>: as a
     * string that can be fed straight back into another file tool.
     *
     * <p>The search tools walk a caller-supplied root and used to emit paths
     * relativised against that root. Those strings are not valid input —
     * {@link #resolve} interprets a relative path against the process working
     * directory, not against whatever root the previous call happened to use.
     * A grep under {@code …/repos/vance/client} reporting
     * {@code packages/components/src/x.ts} therefore sent every follow-up read
     * to {@code <cwd>/packages/components/src/x.ts}, which does not exist —
     * and the LLM has no way to recover the missing prefix.
     *
     * <p>So: working-directory-relative while the file is under the working
     * directory (short, and what {@link #resolve} expects), absolute
     * otherwise.
     */
    public static String toToolPath(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (!abs.startsWith(cwd)) return abs.toString();
        String rel = cwd.relativize(abs).toString();
        return rel.isEmpty() ? "." : rel;
    }

    /**
     * Turns a failed file access into a message the LLM can act on.
     *
     * <p>The naive {@code "Read failed: " + e.getMessage()} is close to
     * useless here: {@link NoSuchFileException#getMessage()} returns the bare
     * path, so the model saw {@code "Read failed: /some/path.ts"} and could
     * not tell a missing file from a permission problem, a directory, or a
     * binary. Nothing in that string suggests a next step, so the observed
     * behaviour was to retry the same path in a slightly different spelling
     * until the turn's iteration budget ran out.
     *
     * <p>Each branch therefore names the actual condition and, where there is
     * one, the tool that would have worked.
     */
    public static String describeFailure(Path file, Exception e) {
        Path abs = file.toAbsolutePath().normalize();
        if (e instanceof NoSuchFileException) {
            return "No such file: " + abs
                    + " (a relative path is resolved against the working directory "
                    + Path.of("").toAbsolutePath().normalize() + ")";
        }
        if (e instanceof AccessDeniedException) {
            return "Permission denied: " + abs;
        }
        if (e instanceof CharacterCodingException) {
            return "Not a UTF-8 text file: " + abs + " — this looks like binary content";
        }
        if (Files.isDirectory(file)) {
            return "Path is a directory, not a file: " + abs + " — use client_file_list";
        }
        return "Read failed: " + abs + " — " + e;
    }

    private static String expandHome(String raw) {
        if (raw.equals("~")) {
            return System.getProperty("user.home");
        }
        if (raw.startsWith("~/")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }
}
