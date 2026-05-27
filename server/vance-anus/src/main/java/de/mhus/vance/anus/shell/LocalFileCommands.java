package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Local-filesystem helpers — list, cat, mkdir, rm — for inspecting and
 * tweaking the filesystem on the host running the anus shell. Useful
 * on Mini where you'd otherwise need a separate {@code kubectl exec}
 * round-trip.
 *
 * <p>Paths are resolved relative to the shell's current working
 * directory ({@code Paths.get("")}). Symlinks are not followed when
 * deleting — same posture as POSIX {@code rm -r}.
 */
@ShellComponent
@RequiresAuth
public class LocalFileCommands {

    private static final Logger log = LoggerFactory.getLogger(LocalFileCommands.class);

    private static final DateTimeFormatter MTIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Truncation threshold for {@code lcat} so a huge file can't lock the shell. */
    private static final int MAX_CAT_BYTES = 2 * 1024 * 1024; // 2 MiB

    @ShellMethod(key = "lls", value = "List a local directory (default: cwd).")
    public String lls(
            @ShellOption(value = {"--path", "-p"}, defaultValue = ".",
                    help = "Directory to list. Defaults to '.' (cwd).")
            String pathArg,
            @ShellOption(value = {"--all", "-a"}, defaultValue = "false",
                    help = "Include hidden entries (names starting with '.').")
            boolean all) {

        Path dir = Paths.get(pathArg).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            return "No such path: " + dir;
        }
        if (!Files.isDirectory(dir)) {
            return dir + " is not a directory.";
        }

        List<Entry> rows = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (!all && name.startsWith(".")) {
                    continue;
                }
                boolean isDir = Files.isDirectory(p);
                long size = isDir ? -1 : safeSize(p);
                Instant mtime = safeMtime(p);
                rows.add(new Entry(name, isDir, size, mtime));
            }
        } catch (IOException e) {
            return "List FAILED: " + e.getMessage();
        }
        if (rows.isEmpty()) {
            return "(empty directory)";
        }
        rows.sort(Comparator
                .comparing(Entry::isDir).reversed()    // dirs first
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));

        StringBuilder out = new StringBuilder();
        out.append(dir).append('\n');
        for (Entry e : rows) {
            out.append(formatRow(e)).append('\n');
        }
        out.append(rows.size()).append(" entries.");
        return out.toString();
    }

    @ShellMethod(key = "lcat", value = "Print a local file's contents (UTF-8, truncated past 2 MiB).")
    public String lcat(
            @ShellOption(value = {"--file", "-f"},
                    help = "Path to the file.")
            String fileArg) {

        Path file = Paths.get(fileArg).toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            return "No such file: " + file;
        }
        if (!Files.isRegularFile(file)) {
            return file + " is not a regular file.";
        }
        try {
            long size = Files.size(file);
            if (size > MAX_CAT_BYTES) {
                byte[] head = new byte[MAX_CAT_BYTES];
                try (var in = Files.newInputStream(file)) {
                    int read = in.read(head);
                    String body = new String(head, 0, Math.max(0, read), StandardCharsets.UTF_8);
                    return body
                            + "\n\n— truncated; file is "
                            + size + " bytes total, showed first "
                            + MAX_CAT_BYTES + " bytes.";
                }
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Read FAILED: " + e.getMessage();
        }
    }

    @ShellMethod(key = "lmkdir", value = "Create a local directory (creates parents).")
    public String lmkdir(
            @ShellOption(value = {"--path", "-p"},
                    help = "Directory path to create. Parents are created as needed.")
            String pathArg) {

        Path dir = Paths.get(pathArg).toAbsolutePath().normalize();
        if (Files.exists(dir)) {
            if (Files.isDirectory(dir)) {
                return "Already exists: " + dir;
            }
            return "Path exists and is not a directory: " + dir;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return "Mkdir FAILED: " + e.getMessage();
        }
        log.info("lmkdir created '{}'", dir);
        return "Created: " + dir;
    }

    @ShellMethod(key = "lwrite",
            value = "Write text to a local file (UTF-8). Default overwrites; use --append/-a to append.")
    public String lwrite(
            @ShellOption(value = {"--file", "-f"},
                    help = "Path to the file. Created if missing.")
            String fileArg,
            @ShellOption(value = {"--content", "-c"},
                    help = "Text content to write. Pass an empty string to truncate / no-op append.")
            String content,
            @ShellOption(value = {"--append", "-a"}, defaultValue = "false",
                    help = "Append instead of overwriting.")
            boolean append,
            @ShellOption(value = {"--create-parents", "-P"}, defaultValue = "false",
                    help = "Create missing parent directories before writing.")
            boolean createParents,
            @ShellOption(value = {"--newline", "-n"}, defaultValue = "false",
                    help = "Append a trailing newline after content (handy for log-style files).")
            boolean addNewline) {

        Path file = Paths.get(fileArg).toAbsolutePath().normalize();
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            if (!createParents) {
                return "Parent directory missing: " + parent
                        + " (use --create-parents to auto-create).";
            }
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                return "Mkdir parent FAILED: " + e.getMessage();
            }
        }
        if (Files.exists(file) && Files.isDirectory(file)) {
            return "Refusing: target is a directory: " + file;
        }
        String payload = addNewline && !content.endsWith("\n") ? content + "\n" : content;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try {
            if (append) {
                Files.write(file, bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            } else {
                Files.write(file, bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            return "Write FAILED: " + e.getMessage();
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        log.info("lwrite {} '{}' (+{} bytes; file now {} bytes)",
                append ? "appended" : "wrote", file, bytes.length, size);
        return (append ? "Appended " : "Wrote ")
                + bytes.length + " bytes to " + file
                + " (file now " + (size < 0 ? "?" : size) + " bytes).";
    }

    @ShellMethod(key = "lwget",
            value = "Download an http(s) URL to a local file. Follows redirects, "
                    + "overwrites the target by default.")
    public String lwget(
            @ShellOption(value = {"--file", "-f"},
                    help = "Local destination path. Overwritten if it exists "
                            + "(use --no-clobber to refuse).")
            String fileArg,
            @ShellOption(value = {"--url", "-u"},
                    help = "Absolute http:// or https:// URL.")
            String urlArg,
            @ShellOption(value = {"--no-clobber"}, defaultValue = "false",
                    help = "Refuse to overwrite an existing file.")
            boolean noClobber,
            @ShellOption(value = {"--create-parents", "-P"}, defaultValue = "false",
                    help = "Create missing parent directories before writing.")
            boolean createParents,
            @ShellOption(value = {"--timeout", "-t"}, defaultValue = "60",
                    help = "Per-request timeout in seconds (connect + total).")
            int timeoutSeconds) {

        URI uri;
        try {
            uri = new URI(urlArg);
        } catch (URISyntaxException e) {
            return "Invalid URL: " + e.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return "Only http:// and https:// URLs are supported (got '" + scheme + "').";
        }

        Path file = Paths.get(fileArg).toAbsolutePath().normalize();
        if (Files.exists(file)) {
            if (Files.isDirectory(file)) {
                return "Refusing: target is a directory: " + file;
            }
            if (noClobber) {
                return "Refusing: target exists and --no-clobber is set: " + file;
            }
        }
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            if (!createParents) {
                return "Parent directory missing: " + parent
                        + " (use --create-parents to auto-create).";
            }
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                return "Mkdir parent FAILED: " + e.getMessage();
            }
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("User-Agent", "vance-anus/1.0")
                .GET()
                .build();
        HttpResponse<Path> response;
        try {
            response = http.send(request,
                    HttpResponse.BodyHandlers.ofFile(file));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Download interrupted.";
        } catch (IOException e) {
            return "Download FAILED: " + e.getMessage();
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // The BodyHandler.ofFile already wrote the (error) body; clean it up
            // so the user doesn't end up with an HTML 404 page sitting where they
            // expected the asset.
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Best-effort; user can lrm it themselves.
            }
            return "HTTP " + status + " — file not written.";
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        log.info("lwget {} -> '{}' ({} bytes, HTTP {})",
                uri, file, size, status);
        return "Downloaded " + (size < 0 ? "?" : size) + " bytes from " + uri
                + " (HTTP " + status + ") to " + file;
    }

    @ShellMethod(key = "lrm", value = "Delete a local file or directory. Use --recursive for directories.")
    public String lrm(
            @ShellOption(value = {"--path", "-p"},
                    help = "File or directory to delete.")
            String pathArg,
            @ShellOption(value = {"--recursive", "-r"}, defaultValue = "false",
                    help = "Required to delete a non-empty directory.")
            boolean recursive,
            @ShellOption(value = {"--force"}, defaultValue = "false",
                    help = "Do not error if the path is missing.")
            boolean force) {

        Path target = Paths.get(pathArg).toAbsolutePath().normalize();
        if (!Files.exists(target)) {
            return force ? "(not present, --force) " + target : "No such path: " + target;
        }

        // Cheap safety rail — if the user typed an absolute root path or
        // a single-segment path (like '/'), refuse. Anyone who actually
        // needs to delete a root-level dir can step outside this tool.
        Path normalized = target;
        if (normalized.getNameCount() == 0) {
            return "Refusing to delete root-like path: " + target;
        }

        try {
            if (Files.isDirectory(target)) {
                if (!recursive) {
                    return "Refusing to delete directory without --recursive (-r): " + target;
                }
                deleteRecursively(target);
                log.warn("lrm removed directory '{}'", target);
                return "Removed directory: " + target;
            }
            Files.delete(target);
            log.warn("lrm removed file '{}'", target);
            return "Removed file: " + target;
        } catch (IOException e) {
            return "Delete FAILED: " + e.getMessage();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static @Nullable Instant safeMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatRow(Entry e) {
        String typeChar = e.isDir() ? "d" : "-";
        String size = e.isDir() ? "         <dir>"
                : String.format("%14s", humanSize(e.size()));
        String mtime = e.mtime() == null ? "                " : MTIME_FORMAT.format(e.mtime());
        String name = e.name() + (e.isDir() ? "/" : "");
        return String.format("%s %s %s  %s", typeChar, size, mtime, name);
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MiB", bytes / (1024.0 * 1024));
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    private record Entry(String name, boolean isDir, long size, @Nullable Instant mtime) {}
}
