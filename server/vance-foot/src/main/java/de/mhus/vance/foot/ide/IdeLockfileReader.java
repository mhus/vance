package de.mhus.vance.foot.ide;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Claude Code IDE plugin lockfiles from {@code ~/.claude/ide/}, parses
 * them into {@link IdeLockfile} records and applies the workspace-match rules
 * described in {@code planning/foot-ide-bridge.md} §1.2.
 *
 * <p>Stateless — instantiated once per Foot session via
 * {@link #ofDefaultDirectory()} or with an explicit directory in tests. The
 * class never writes to or deletes files inside the lockfile directory; that
 * stays plugin/Claude territory (§7).
 */
@Slf4j
public final class IdeLockfileReader {

    private static final String LOCK_SUFFIX = ".lock";
    /** Connect timeout for the liveness probe — fast on localhost, no point waiting longer. */
    private static final int PROBE_TIMEOUT_MS = 250;

    private final Path directory;
    private final ObjectMapper json = JsonMapper.builder().build();

    public IdeLockfileReader(Path directory) {
        this.directory = directory;
    }

    /** Default directory: {@code ~/.claude/ide/}. */
    public static IdeLockfileReader ofDefaultDirectory() {
        return new IdeLockfileReader(Paths.get(System.getProperty("user.home"), ".claude", "ide"));
    }

    /**
     * Lists all readable {@code <port>.lock} files in the configured directory.
     * Files that fail to parse are skipped with a warning — one bad lockfile
     * must never break discovery.
     */
    public List<IdeLockfile> readAll() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<IdeLockfile> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + LOCK_SUFFIX)) {
            for (Path file : stream) {
                IdeLockfile parsed = parse(file);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list lockfile directory {}: {}", directory, e.toString());
            return List.of();
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Picks the lockfile that should be used for the given working directory,
     * applying the discovery order from §1.2:
     *
     * <ol>
     *   <li>If {@code CLAUDE_CODE_SSE_PORT} is set, the matching lockfile wins
     *       outright — workspace match is skipped.</li>
     *   <li>Otherwise, the lockfile whose {@code workspaceFolders} contain
     *       {@code cwd} (after NFC normalisation, §1.5) wins. If multiple
     *       match, the newest by {@code mtime} wins (§5 tip 9).</li>
     *   <li>The chosen file is probed: PID alive AND TCP connect to its
     *       port succeeds. If not, it is skipped and the next candidate is
     *       tried.</li>
     * </ol>
     */
    public Optional<IdeLockfile> pickFor(Path cwd, @Nullable String envSsePort) {
        List<IdeLockfile> all = readAll();
        if (all.isEmpty()) {
            return Optional.empty();
        }

        if (envSsePort != null && !envSsePort.isBlank()) {
            int port;
            try {
                port = Integer.parseInt(envSsePort.trim());
            } catch (NumberFormatException e) {
                log.warn("CLAUDE_CODE_SSE_PORT is not numeric: {}", envSsePort);
                port = -1;
            }
            if (port > 0) {
                for (IdeLockfile lf : all) {
                    if (lf.port() == port && isLive(lf)) {
                        return Optional.of(lf);
                    }
                }
                log.warn("CLAUDE_CODE_SSE_PORT={} but no live lockfile matches.", port);
            }
        }

        String normalizedCwd = normalise(cwd.toAbsolutePath().toString());
        List<IdeLockfile> matches = new ArrayList<>();
        for (IdeLockfile lf : all) {
            for (String workspace : lf.workspaceFolders()) {
                if (cwdMatches(normalizedCwd, normalise(workspace))) {
                    matches.add(lf);
                    break;
                }
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        matches.sort(Comparator.comparingLong((IdeLockfile lf) -> lastModified(lf.path())).reversed());
        for (IdeLockfile candidate : matches) {
            if (isLive(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * True when the lockfile's IDE process is still alive AND we can open a
     * TCP connection to the advertised port. Two-step check because either
     * alone is unreliable: a stale lockfile might happen to share the PID
     * with another process, and a port can be bound by something else.
     */
    public boolean isLive(IdeLockfile lf) {
        if (!isPidAlive(lf.pid())) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", lf.port()), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private @Nullable IdeLockfile parse(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(LOCK_SUFFIX)) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(fileName.substring(0, fileName.length() - LOCK_SUFFIX.length()));
        } catch (NumberFormatException e) {
            log.debug("Skipping non-numeric lockfile: {}", file);
            return null;
        }
        try {
            JsonNode root = json.readTree(Files.readString(file));
            List<String> workspaces = new ArrayList<>();
            JsonNode wsNode = root.get("workspaceFolders");
            if (wsNode != null && wsNode.isArray()) {
                for (JsonNode entry : wsNode) {
                    if (entry.isString()) {
                        workspaces.add(entry.asString());
                    }
                }
            }
            String authToken = textOrNull(root, "authToken");
            if (authToken == null || authToken.isBlank()) {
                log.warn("Lockfile {} has no authToken — skipping", file);
                return null;
            }
            long pid = root.has("pid") ? root.get("pid").asLong(0) : 0;
            return new IdeLockfile(
                    port,
                    file,
                    List.copyOf(workspaces),
                    pid,
                    textOrNull(root, "ideName"),
                    textOrNull(root, "transport"),
                    root.has("runningInWindows") && root.get("runningInWindows").asBoolean(false),
                    authToken);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not parse lockfile {}: {}", file, e.toString());
            return null;
        }
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /**
     * NFC-normalises a path string for comparison. macOS APIs sometimes
     * decompose Unicode (NFD), the IDE stores NFC — without this step,
     * paths with umlauts/CJK would mismatch (§1.5).
     */
    static String normalise(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC);
    }

    /**
     * Workspace match: cwd is exactly the workspace, or sits below it.
     * Compared on the normalised forms.
     */
    static boolean cwdMatches(String cwd, String workspace) {
        if (cwd.equals(workspace)) {
            return true;
        }
        String prefix = workspace.endsWith("/") ? workspace : workspace + "/";
        return cwd.startsWith(prefix);
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean isPidAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
