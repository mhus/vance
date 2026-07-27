package de.mhus.vance.addon.brain.rlang;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Evaluate an R script on a running Rserve daemon (default
 * {@code localhost:6311}) and return what {@code print()} / {@code cat()}
 * wrote, plus the value of the final expression.
 *
 * <p>The user-supplied script is shipped as an R string variable
 * ({@code vance_script}) and evaluated via
 * {@code source(textConnection(vance_script))}. We don't paste the
 * script body into another R expression — that would force the agent
 * to escape quotes, newlines, and dollar signs which they get wrong
 * about half the time. As a string variable, anything goes verbatim.
 *
 * <p>The eval itself is delegated to {@link RExecutionService} (the shared
 * Rserve core, also used by {@code RDamogranTask}). This tool owns the
 * consumer-specific parts: minting/cleaning a temp working dir when the caller
 * didn't pin one, emitting progress pings, and importing the files the run
 * produced as Vance documents (see {@link #importOutputs}).
 *
 * <p>Limitations of this iteration:
 * <ul>
 *   <li>Single shared Rserve session per connection — Rserve forks
 *       a child process per client connection, so simultaneous tool
 *       calls don't collide, but the brain doesn't yet pool
 *       connections. Each tool call opens + closes.</li>
 *   <li>No streaming progress mid-script. The tool emits a
 *       {@code FETCH} ping before the eval and an {@code INFO} ping
 *       when it returns.</li>
 * </ul>
 */
@Component
@Slf4j
public class RScriptTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "script", Map.of(
                            "type", "string",
                            "description", "R code to evaluate. May "
                                    + "be multi-line. Quote your "
                                    + "strings normally — the script "
                                    + "is shipped verbatim, no escape "
                                    + "tricks needed."),
                    "workingDir", Map.of(
                            "type", "string",
                            "description", "Optional absolute path "
                                    + "for the R session's working "
                                    + "directory. If set, the tool "
                                    + "runs setwd() before your "
                                    + "script. Use for ggsave() "
                                    + "outputs, read.csv() of local "
                                    + "files, etc.")),
            "required", List.of("script"));

    private final RserveHealth health;
    private final RExecutionService rExecutionService;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public RScriptTool(RserveHealth health,
                       RExecutionService rExecutionService,
                       ThinkProcessService thinkProcessService,
                       ProgressEmitter progressEmitter,
                       DocumentService documentService,
                       DocumentLinkBuilder linkBuilder,
                       de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.health = health;
        this.rExecutionService = rExecutionService;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.contextFactory = contextFactory;
    }

    @Override
    public String name() {
        return "r_script";
    }

    @Override
    public String description() {
        return "Evaluate an R script on the brain's Rserve daemon. "
                + "Returns the captured stdout (from print(), cat()) "
                + "plus the final expression's value as text. Use "
                + "this for stats / data-frame / ggplot / time-"
                + "series tasks where R's ecosystem (dplyr, tidyr, "
                + "forecast, Bioconductor, …) beats Python. The "
                + "script body is shipped verbatim — no quote-"
                + "escaping needed. The tool runs in a fresh "
                + "temporary working dir by default; any files the "
                + "script writes there (ggsave PNGs, write.csv "
                + "tables, PDFs, …) are auto-imported as Vance "
                + "Documents and returned in the `outputs` array "
                + "with `vanceUri` and `markdownLink` you can embed "
                + "in chat. Pass `workingDir` to use a pinned path "
                + "instead (only files *new* in that dir are "
                + "imported).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        // NOT "read-only": r_script evaluates arbitrary R on the Rserve daemon
        // (R can system()/system2() shell-exec on the brain host, read/write any
        // file) and imports produced files as Vance documents. The "read-only"
        // label would derive ToolSafety.SAFE_PROBE (Tool.safety()) and let the
        // Agrajag probe tools (tool_probe_as_system/_user) re-run arbitrary R.
        return Set.of("compute");
    }

    @Override
    public de.mhus.vance.api.tools.ToolSafety safety() {
        // Arbitrary code execution + document writes → never a safe probe.
        return de.mhus.vance.api.tools.ToolSafety.MUTATING;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String script = asString(params == null ? null : params.get("script"));
        if (script == null || script.isBlank()) {
            throw new ToolException("'script' is required");
        }
        String workingDir = asString(params == null ? null : params.get("workingDir"));

        ThinkProcessDocument process = loadProcess(ctx);
        emit(process, StatusTag.FETCH,
                "Evaluating R script on Rserve "
                        + health.properties().getHost() + ":"
                        + health.properties().getPort() + "…");

        // Determine the working dir. If the caller didn't pin one,
        // we mint a temp dir so the script can write files (plots,
        // CSVs) and we'll auto-import them as Documents afterwards.
        // When the caller pins an explicit path we honour it but
        // still scan for new files at the end.
        boolean ownsTempDir = workingDir == null || workingDir.isBlank();
        Path effectiveDir;
        if (ownsTempDir) {
            try {
                effectiveDir = Files.createTempDirectory("vance-r-");
            } catch (IOException e) {
                throw new ToolException(
                        "Could not create temp working dir: " + e.getMessage());
            }
        } else {
            effectiveDir = Path.of(workingDir);
        }

        try {
            RExecutionService.Result res = rExecutionService.evaluate(script, effectiveDir);
            if (!res.ok()) {
                emit(process, StatusTag.INFO, "R script failed: " + res.errorMessage());
                throw new ToolException(res.errorMessage());
            }

            log.info("RScriptTool tenant='{}' workingDir='{}' "
                            + "outputBytes={} elapsedSec={}",
                    ctx.tenantId(), workingDir == null ? "(default)" : workingDir,
                    res.contentLength(), res.elapsedSec());
            emit(process, StatusTag.INFO,
                    String.format(Locale.ROOT,
                            "R script done in %.2fs (%d chars).",
                            res.elapsedSec(), res.contentLength()));

            // ── Import the run's new files as Documents ──
            List<Map<String, Object>> outputs = importOutputs(res.newFiles(), ctx, process);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rVersion", res.rVersion());
            out.put("elapsedSec", res.elapsedSec());
            out.put("contentLength", res.contentLength());
            out.put("truncated", res.truncated());
            out.put("text", res.text());
            if (!outputs.isEmpty()) {
                out.put("outputs", outputs);
            }
            return out;
        } finally {
            if (ownsTempDir) {
                cleanupTempDir(effectiveDir);
            }
        }
    }

    /**
     * Classify each of the run's {@code newFiles} by extension, import it as a
     * Vance Document under {@code r-outputs/&lt;timestamp&gt;/&lt;name&gt;}, and
     * return a list of result maps with {@code kind}, {@code path},
     * {@code vanceUri}, {@code markdownLink}, {@code size}.
     *
     * <p>When the tool context has no {@code projectId} (rare — admin flows),
     * import is skipped and the files are listed with just {@code kind} +
     * {@code localPath} so the caller can at least see what was produced.
     */
    private List<Map<String, Object>> importOutputs(
            List<Path> newFiles,
            ToolInvocationContext ctx,
            @Nullable ThinkProcessDocument process) {
        if (newFiles.isEmpty()) return List.of();

        String projectId = ctx.projectId();
        if (projectId == null) {
            // No project context — return only local file info so the
            // caller sees something happened, but skip the import.
            log.warn("RScriptTool produced {} output(s) but ctx has no "
                    + "projectId — skipping document import", newFiles.size());
            List<Map<String, Object>> minimal = new ArrayList<>();
            for (Path f : newFiles) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", kindForExtension(f.getFileName().toString()));
                entry.put("localPath", f.toString());
                entry.put("size", sizeQuiet(f));
                minimal.add(entry);
            }
            return minimal;
        }

        String stamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        List<Map<String, Object>> results = new ArrayList<>();
        for (Path f : newFiles) {
            String fileName = f.getFileName().toString();
            String kind = kindForExtension(fileName);
            String mime = mimeForExtension(fileName);
            String docPath = "r-outputs/" + stamp + "/" + fileName;
            try (InputStream in = Files.newInputStream(f)) {
                DocumentDocument created = documentService.create(
                        ctx.tenantId(),
                        projectId,
                        docPath,
                        null,                 // title
                        List.of("r-output"),  // tags
                        mime,
                        in,
                        ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), docPath));
                String vanceUri = DocumentLinkBuilder.buildVanceUri(
                        null, created.getPath(), kind,
                        DocumentLinkBuilder.defaultModeForKind(kind));
                String markdownLink = linkBuilder.linkFor(created, null);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", kind);
                entry.put("path", created.getPath());
                entry.put("vanceUri", vanceUri);
                entry.put("markdownLink", markdownLink);
                entry.put("size", created.getSize());
                results.add(entry);
                emit(process, StatusTag.INFO,
                        "Imported R output '" + fileName + "' as " + kind + " document.");
            } catch (Exception e) {
                log.warn("Could not import R output {}: {}", f, e.getMessage());
            }
        }
        return results;
    }

    /** Pick a Vance kind from the filename extension. */
    static String kindForExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        return switch (ext) {
            case "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "image";
            case "svg" -> "svg";
            case "pdf" -> "pdf";
            case "csv", "tsv" -> "records";
            case "json" -> "data";
            case "md", "markdown" -> "markdown";
            case "txt", "log" -> "text";
            case "html", "htm" -> "html";
            default -> "document";
        };
    }

    /** Pick a mime type from the filename extension. */
    static String mimeForExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        return switch (ext) {
            case "png"  -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif"  -> "image/gif";
            case "bmp"  -> "image/bmp";
            case "svg"  -> "image/svg+xml";
            case "pdf"  -> "application/pdf";
            case "csv"  -> "text/csv";
            case "tsv"  -> "text/tab-separated-values";
            case "json" -> "application/json";
            case "md", "markdown" -> "text/markdown";
            case "txt", "log" -> "text/plain";
            case "html", "htm" -> "text/html";
            default     -> "application/octet-stream";
        };
    }

    private static long sizeQuiet(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1L; }
    }

    /** Recursive cleanup — temp dir might contain R-side garbage
     *  (cache files, .Rhistory, etc.) on top of the user files. */
    private static void cleanupTempDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) {
                            log.warn("Could not delete {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not walk temp dir {}: {}", dir, e.getMessage());
        }
    }

    private @Nullable ThinkProcessDocument loadProcess(ToolInvocationContext ctx) {
        if (ctx == null || ctx.processId() == null) return null;
        Optional<ThinkProcessDocument> opt = thinkProcessService.findById(ctx.processId());
        return opt.orElse(null);
    }

    private void emit(@Nullable ThinkProcessDocument process,
                      StatusTag tag, String text) {
        if (process == null) return;
        progressEmitter.emitStatus(process, tag, text);
    }

    private static @Nullable String asString(@Nullable Object v) {
        return v == null ? null : v.toString();
    }
}
