package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.StateSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Data owner of the Damogran <b>state store</b> — a per-document, per-type
 * directory tree inside a WORK compose workspace that lets the code-executing
 * tasks ({@code exec}/{@code python}/{@code js}/{@code r}) carry a JSON-shaped
 * {@code state} object between runs of the same document, plus persisted
 * {@code header}/{@code footer} code fragments the handlers wrap around a script.
 *
 * <p>Layout: {@code <workspace>/_damogran-state/<docKey>/<type>/{header,footer,cache.*}}.
 * The {@code docKey} is the run's {@link DamogranContext#stateKey()} (compose
 * document / page path) — so distinct documents don't share state even when they
 * share a named workspace, while blocks of the same page do. Kurzlebig: the store
 * lives in the workspace and dies with it (clear/unload/pod-restart). WORK only —
 * a CLIENT/DAEMON run has no server-side path and carries no state.
 *
 * <p>See {@code planning/damogran-state.md}.
 */
@Slf4j
@Service
public class DamogranStateService {

    /** Internal, {@code _}-prefixed store root inside the workspace (hidden by convention). */
    static final String STATE_ROOT = "_damogran-state";
    static final String HEADER_FILE = "header";
    static final String FOOTER_FILE = "footer";
    static final String CACHE_JSON = "cache.json";
    static final String CACHE_ENV = "cache.env";

    private final WorkspaceService workspaceService;

    public DamogranStateService(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Apply the manifest's {@code state:} operations in order, before the tasks
     * run. No-op when the run has no state key (no document identity) or is not a
     * WORK workspace (no server-side path). Operations:
     * <ul>
     *   <li>{@code delete} → wipe the whole {@code <docKey>/} store;</li>
     *   <li>{@code init} → empty (recreate) the type folder;</li>
     *   <li>{@code header}/{@code footer} → write that file (creating the folder).</li>
     * </ul>
     */
    public void applyOps(DamogranContext ctx, List<StateSpec> ops) {
        if (ops.isEmpty()) {
            return;
        }
        String key = ctx.stateKey();
        if (key == null || ctx.workspacePath() == null) {
            log.debug("Damogran state: ignoring {} op(s) — no state key or not a WORK workspace "
                    + "(stateKey={}, work={})", ops.size(), key, ctx.workspacePath() != null);
            return;
        }
        for (StateSpec op : ops) {
            if (op.delete()) {
                deleteTree(baseDir(ctx, key));
                log.trace("Damogran state: deleted store {}", key);
                continue;
            }
            String type = op.type(); // parser guarantees non-null here
            if (type == null) {
                continue;
            }
            if (op.init()) {
                Path dir = typeDir(ctx, key, type);
                deleteTree(dir);
                createDir(dir);
                log.trace("Damogran state: init {}/{}", key, type);
            }
            if (op.header() != null) {
                workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                        relType(key, type) + "/" + HEADER_FILE, op.header());
            }
            if (op.footer() != null) {
                workspaceService.write(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(),
                        relType(key, type) + "/" + FOOTER_FILE, op.footer());
            }
        }
    }

    /**
     * The active state directory for a task {@code type}, or {@code null} when
     * state is not active for this run — the switch is <b>folder existence</b>:
     * a type folder exists only after an {@code init}/{@code header}/{@code footer}
     * op created it, so a run without a prior state op runs plain (no wrapping).
     */
    public @Nullable StateDir resolve(DamogranContext ctx, String type) {
        String key = ctx.stateKey();
        if (key == null || ctx.workspacePath() == null) {
            return null;
        }
        Path dir = typeDir(ctx, key, type);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        return new StateDir(relType(key, type), dir, cacheFileName(type));
    }

    /** The store base dir for a docKey (all types), whether or not it exists. */
    Path baseDir(DamogranContext ctx, String key) {
        return workspaceService.resolve(
                ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), relBase(key));
    }

    /**
     * The state types with an existing folder under this run's store, sorted;
     * empty when there is no state key, not a WORK workspace, or no store yet.
     * For {@code state-status} inspection.
     */
    public List<String> listTypes(DamogranContext ctx) {
        String key = ctx.stateKey();
        if (key == null || ctx.workspacePath() == null) {
            return List.of();
        }
        Path base = baseDir(ctx, key);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(base)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DamogranException("state: cannot list types under " + base + ": " + e.getMessage(), e);
        }
    }

    private Path typeDir(DamogranContext ctx, String key, String type) {
        return workspaceService.resolve(
                ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), relType(key, type));
    }

    private static String relBase(String key) {
        return STATE_ROOT + "/" + normalizeKey(key);
    }

    private static String relType(String key, String type) {
        return relBase(key) + "/" + type;
    }

    /** Normalize a doc-path key into a workspace-relative sub-path (containment enforced downstream). */
    private static String normalizeKey(String key) {
        String k = key.replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        while (k.endsWith("/")) {
            k = k.substring(0, k.length() - 1);
        }
        return k.isBlank() ? "_" : k;
    }

    /** exec/bash uses a shell env dump; json-serialized types use cache.json. */
    static String cacheFileName(String type) {
        return "exec".equals(type) ? CACHE_ENV : CACHE_JSON;
    }

    /**
     * Render {@code s} as a JSON string literal (double-quoted, escaped) — also a
     * valid Python / JavaScript string literal, used to embed a cache path into a
     * generated wrapper script without an injection hole.
     */
    public static String jsonQuote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** POSIX single-quote {@code s} for safe embedding in a shell script. */
    public static String posixQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new DamogranException("state: cannot create dir " + dir + ": " + e.getMessage(), e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Damogran state: cleanup failed for {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            throw new DamogranException("state: cannot wipe " + dir + ": " + e.getMessage(), e);
        }
    }

    /**
     * A resolved, active state directory for one task type. Exposes the
     * workspace-relative paths (for {@link WorkspaceService} writes) and absolute
     * paths (for native reads by the handler) of the {@code header}/{@code footer}/
     * {@code cache} files, plus content-read helpers ({@code ""} when a file is
     * absent).
     *
     * @param relDir        workspace-relative type directory
     *                      ({@code _damogran-state/<docKey>/<type>})
     * @param absDir        absolute type directory on the pod
     * @param cacheFileName {@code cache.json} or {@code cache.env}
     */
    public record StateDir(String relDir, Path absDir, String cacheFileName) {

        public String cacheRel() {
            return relDir + "/" + cacheFileName;
        }

        public Path headerPath() {
            return absDir.resolve(HEADER_FILE);
        }

        public Path footerPath() {
            return absDir.resolve(FOOTER_FILE);
        }

        public Path cachePath() {
            return absDir.resolve(cacheFileName);
        }

        public boolean existsCache() {
            return Files.isRegularFile(cachePath());
        }

        public String readHeader() {
            return readOrEmpty(absDir.resolve(HEADER_FILE));
        }

        public String readFooter() {
            return readOrEmpty(absDir.resolve(FOOTER_FILE));
        }

        public String readCache() {
            return readOrEmpty(cachePath());
        }

        private static String readOrEmpty(Path file) {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DamogranException("state: read failed for " + file + ": " + e.getMessage(), e);
            }
        }
    }
}
