package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.session.exchange.SessionExchangeService;
import de.mhus.vance.shared.session.exchange.SessionExportEmitter;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportFormat;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportRequest;
import de.mhus.vance.shared.session.exchange.SessionImportModel.ImportResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Export and import whole sessions against the local Mongo connection.
 *
 * <p>Both directions go straight through {@link SessionExchangeService}
 * (shared) — no Brain round-trip — because every session entity lives in
 * {@code vance-shared}. Import reconstructs (VANCE format) or synthesises
 * (CLAUDE format) the chat process so the imported session is continuable.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class SessionCommands {

    private final SessionService sessionService;
    private final SessionExchangeService exchangeService;

    // ─── Export ─────────────────────────────────────────────────────────

    @Command(name = {"session", "export"}, description = "Export one session to a .jsonl file (Vance format).")
    public String export(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "session", shortName = 's', required = true) String sessionId,
            @Option(longName = "out", shortName = 'o', required = true,
                    description = "Target file, or a directory (a session-<id>-<ts>.jsonl name is generated).")
            String out) {
        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElse(null);
        if (session == null) {
            return "Session '" + sessionId + "' not found in tenant '" + tenant + "'.";
        }
        try {
            Path target = resolveExportTarget(Path.of(out), sessionId);
            writeSession(session, target);
            return "Exported session '" + sessionId + "' → " + target;
        } catch (IOException e) {
            return "Export failed: " + e.getMessage();
        }
    }

    @Command(name = {"session", "export-all"},
            description = "Export every session of a tenant (or one project) into a directory.")
    public String exportAll(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "project", shortName = 'p') @Nullable String project,
            @Option(longName = "out", shortName = 'o', required = true, description = "Target directory (created if missing).") String out) {
        List<SessionDocument> sessions = project != null
                ? sessionService.listForProject(tenant, project)
                : sessionService.listForTenant(tenant);
        if (sessions.isEmpty()) {
            return "(no sessions" + (project != null ? " in project '" + project + "'" : "")
                    + " for tenant '" + tenant + "')";
        }
        Path dir = Path.of(out);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return "Could not create output directory '" + dir + "': " + e.getMessage();
        }
        int ok = 0;
        List<String> failures = new ArrayList<>();
        for (SessionDocument s : sessions) {
            Path target = dir.resolve(
                    SessionExportEmitter.buildExportFilename(s.getSessionId(), Instant.now()));
            try {
                writeSession(s, target);
                ok++;
            } catch (IOException e) {
                failures.add(s.getSessionId() + ": " + e.getMessage());
            }
        }
        StringBuilder sb = new StringBuilder("Exported " + ok + "/" + sessions.size()
                + " sessions → " + dir);
        for (String f : failures) sb.append("\n  FAILED ").append(f);
        return sb.toString();
    }

    // ─── Import ─────────────────────────────────────────────────────────

    @Command(name = {"session", "import"},
            description = "Import a session file (Vance or Claude-Code export) as a new session.")
    public String importSession(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "project", shortName = 'p', required = true) String project,
            @Option(longName = "user", shortName = 'u', required = true) String user,
            @Option(longName = "file", shortName = 'f', required = true) String file,
            @Option(longName = "recipe", shortName = 'r') @Nullable String recipe,
            @Option(longName = "engine", shortName = 'e',
                    description = "Think-engine for the chat process (Claude imports only). Default: arthur.")
            @Nullable String engine,
            @Option(longName = "title", shortName = 't') @Nullable String title,
            @Option(longName = "format",
                    description = "auto | vance | claude",
                    defaultValue = "auto")
            String format,
            @Option(longName = "as-memory",
                    description = "Also seed an ARCHIVED_CHAT memory with the full transcript.",
                    defaultValue = "false")
            boolean asMemory) {
        ImportFormat fmt = parseFormat(format);
        if (fmt == null) {
            return "Unknown --format '" + format + "' (expected auto | vance | claude).";
        }
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            return "File not found: " + path;
        }
        ImportRequest req = new ImportRequest(
                tenant, project, user, null, engine, recipe, title, fmt, asMemory);
        try (InputStream in = Files.newInputStream(path)) {
            ImportResult r = exchangeService.importSession(in, req);
            return renderResult(path.toString(), r);
        } catch (IOException | RuntimeException e) {
            return "Import failed for '" + path + "': " + e.getMessage();
        }
    }

    @Command(name = {"session", "import-all"},
            description = "Import every *.jsonl file in a directory as a new session each.")
    public String importAll(
            @Option(longName = "tenant", shortName = 'T', required = true) String tenant,
            @Option(longName = "project", shortName = 'p', required = true) String project,
            @Option(longName = "user", shortName = 'u', required = true) String user,
            @Option(longName = "dir", shortName = 'd', required = true) String dir,
            @Option(longName = "recipe", shortName = 'r') @Nullable String recipe,
            @Option(longName = "engine", shortName = 'e') @Nullable String engine,
            @Option(longName = "format", defaultValue = "auto") String format,
            @Option(longName = "as-memory", defaultValue = "false") boolean asMemory) {
        ImportFormat fmt = parseFormat(format);
        if (fmt == null) {
            return "Unknown --format '" + format + "' (expected auto | vance | claude).";
        }
        Path directory = Path.of(dir);
        if (!Files.isDirectory(directory)) {
            return "Directory not found: " + directory;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory, "*.jsonl")) {
            ds.forEach(files::add);
        } catch (IOException e) {
            return "Could not list '" + directory + "': " + e.getMessage();
        }
        if (files.isEmpty()) {
            return "(no *.jsonl files in " + directory + ")";
        }
        files.sort(Path::compareTo);
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        for (Path path : files) {
            ImportRequest req = new ImportRequest(
                    tenant, project, user, null, engine, recipe, null, fmt, asMemory);
            try (InputStream in = Files.newInputStream(path)) {
                ImportResult r = exchangeService.importSession(in, req);
                sb.append("  OK ").append(path.getFileName()).append(" → ")
                        .append(renderResult(null, r)).append('\n');
                ok++;
            } catch (IOException | RuntimeException e) {
                sb.append("  FAILED ").append(path.getFileName()).append(": ")
                        .append(e.getMessage()).append('\n');
            }
        }
        return "Imported " + ok + "/" + files.size() + " files into project '"
                + project + "':\n" + sb.toString().stripTrailing();
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private void writeSession(SessionDocument session, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream os = Files.newOutputStream(target)) {
            exchangeService.writeExport(os, session);
        }
    }

    private static Path resolveExportTarget(Path out, String sessionId) {
        if (Files.isDirectory(out)) {
            return out.resolve(SessionExportEmitter.buildExportFilename(sessionId, Instant.now()));
        }
        return out;
    }

    private static @Nullable ImportFormat parseFormat(String s) {
        return switch (s == null ? "" : s.trim().toLowerCase()) {
            case "", "auto" -> ImportFormat.AUTO;
            case "vance" -> ImportFormat.VANCE;
            case "claude" -> ImportFormat.CLAUDE;
            default -> null;
        };
    }

    private static String renderResult(@Nullable String source, ImportResult r) {
        String head = source != null ? "Imported '" + source + "'\n  " : "";
        return head + "session=" + r.sessionId()
                + " format=" + r.detectedFormat()
                + " messages=" + r.messageCount()
                + " memories=" + r.memoryCount()
                + (r.title() != null ? " title='" + r.title() + "'" : "");
    }
}
