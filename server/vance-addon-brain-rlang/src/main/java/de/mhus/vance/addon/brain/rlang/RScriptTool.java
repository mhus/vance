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
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.RList;
import org.rosuda.REngine.Rserve.RConnection;
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
 * <p>Stdout is captured with {@code capture.output(...)} so the
 * tool returns it as a clean line-oriented string. Errors are caught
 * with {@code tryCatch} and turned into a {@code ToolException} that
 * carries the R-level {@code conditionMessage()} verbatim.
 *
 * <p>Limitations of this iteration:
 * <ul>
 *   <li>Single shared Rserve session per connection — Rserve forks
 *       a child process per client connection, so simultaneous tool
 *       calls don't collide, but the brain doesn't yet pool
 *       connections. Each tool call opens + closes.</li>
 *   <li>No file-output discovery — plots saved via {@code ggsave()}
 *       land in Rserve's working directory but aren't imported as
 *       Vance documents yet (iteration 2 will add the workspace-root
 *       handler that does the file-diff + auto-import).</li>
 *   <li>No streaming progress mid-script. The tool emits a
 *       {@code FETCH} ping before the eval and an {@code INFO} ping
 *       when it returns.</li>
 * </ul>
 */
@Component
@Slf4j
public class RScriptTool implements Tool {

    /** Truncation budget for the captured stdout, in characters. */
    static final int MAX_OUTPUT_CHARS = 50_000;

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
    private final RserveDaemonManager daemonManager;
    private final ThinkProcessService thinkProcessService;
    private final ProgressEmitter progressEmitter;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    public RScriptTool(RserveHealth health,
                       RserveDaemonManager daemonManager,
                       ThinkProcessService thinkProcessService,
                       ProgressEmitter progressEmitter,
                       DocumentService documentService,
                       DocumentLinkBuilder linkBuilder) {
        this.health = health;
        this.daemonManager = daemonManager;
        this.thinkProcessService = thinkProcessService;
        this.progressEmitter = progressEmitter;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
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
        return Set.of("read-only");
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

        // Lazy daemon start: on first call (or after a daemon crash),
        // RserveDaemonManager spawns `R CMD Rserve` and blocks until
        // the port answers. Subsequent calls fall through immediately
        // because health.isReachable() short-circuits inside.
        daemonManager.ensureRunning();

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

        long started = System.currentTimeMillis();
        RConnection c;
        try {
            c = new RConnection(health.properties().getHost(),
                    health.properties().getPort());
        } catch (Exception e) {
            if (ownsTempDir) cleanupTempDir(effectiveDir);
            throw new ToolException(
                    "Could not open Rserve connection: " + e.getMessage());
        }

        // File snapshot for the post-eval diff. For an owned temp
        // dir this is empty; for a caller-supplied dir we only
        // pick up things that appeared during this run.
        Set<String> preExisting = snapshot(effectiveDir);

        try {
            {
                c.assign("vance_workdir", effectiveDir.toString());
                REXP setOk = c.eval(
                        "tryCatch({"
                                + " setwd(vance_workdir);"
                                + " list(ok=TRUE, dir=getwd())"
                                + "}, error=function(e) list(ok=FALSE, "
                                + "message=conditionMessage(e)))");
                RList sl = setOk.asList();
                boolean setwdOk = sl.at("ok").asInteger() == 1;
                if (!setwdOk) {
                    throw new ToolException(
                            "setwd('" + effectiveDir + "') failed: "
                                    + sl.at("message").asString());
                }
            }

            c.assign("vance_script", script);
            REXP result = c.eval(
                    "tryCatch({"
                            + " .vance_out <- capture.output("
                            + "   .vance_value <- "
                            + "     source(textConnection(vance_script),"
                            + "            echo=FALSE, max.deparse.length=Inf)$value"
                            + " );"
                            + " list(status='ok',"
                            + "      output=paste(.vance_out, collapse='\\n'),"
                            + "      value=if (is.null(.vance_value)) ''"
                            + "            else paste(capture.output(print(.vance_value)),"
                            + "                       collapse='\\n'))"
                            + "}, error=function(e) list(status='error', "
                            + "                          message=conditionMessage(e)))");

            RList r = result.asList();
            String status = r.at("status").asString();
            if (!"ok".equals(status)) {
                String msg = r.at("message").asString();
                emit(process, StatusTag.INFO, "R script failed: " + msg);
                throw new ToolException("R error: " + msg);
            }

            String output = r.at("output").asString();
            String value = r.at("value").asString();
            String combined = combine(output, value);
            int fullLen = combined.length();
            boolean truncated = fullLen > MAX_OUTPUT_CHARS;
            String body = truncated ? combined.substring(0, MAX_OUTPUT_CHARS) : combined;
            double elapsedSec = (System.currentTimeMillis() - started) / 1000.0;

            log.info("RScriptTool tenant='{}' workingDir='{}' "
                            + "outputBytes={} elapsedSec={}",
                    ctx.tenantId(), workingDir == null ? "(default)" : workingDir,
                    fullLen, elapsedSec);
            emit(process, StatusTag.INFO,
                    String.format(Locale.ROOT,
                            "R script done in %.2fs (%d chars).",
                            elapsedSec, fullLen));

            // ── Auto-discover + import new files as Documents ──
            List<Map<String, Object>> outputs = discoverAndImportOutputs(
                    effectiveDir, preExisting, ctx, process);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rVersion", health.version() == null ? "unknown" : health.version());
            out.put("elapsedSec", elapsedSec);
            out.put("contentLength", fullLen);
            out.put("truncated", truncated);
            out.put("text", body);
            if (!outputs.isEmpty()) {
                out.put("outputs", outputs);
            }
            return out;
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException(
                    "Rserve communication failed: " + e.getMessage());
        } finally {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("Could not close Rserve connection cleanly: {}",
                        e.getMessage());
            }
            if (ownsTempDir) {
                cleanupTempDir(effectiveDir);
            }
        }
    }

    /**
     * Take a flat snapshot (filename-only) of the working dir, so we
     * can diff against it after the R script ran. Returns an empty
     * set when the dir doesn't exist yet (it will after setwd).
     */
    private static Set<String> snapshot(Path dir) {
        if (!Files.isDirectory(dir)) return Set.of();
        try (Stream<Path> stream = Files.list(dir)) {
            Set<String> names = new java.util.HashSet<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            return names;
        } catch (IOException e) {
            log.warn("Could not snapshot working dir {}: {}", dir, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Scan {@code dir} for files that weren't there before the R
     * eval, classify them by extension, import each as a Vance
     * Document under {@code r-outputs/&lt;timestamp&gt;/&lt;name&gt;},
     * and return a list of result maps with {@code kind}, {@code path},
     * {@code vanceUri}, {@code markdownLink}, {@code size}.
     *
     * <p>When the tool context has no {@code projectId} (rare —
     * admin flows), import is skipped and the files are listed with
     * just {@code kind} + {@code localPath} so the caller can at
     * least see what was produced.
     */
    private List<Map<String, Object>> discoverAndImportOutputs(
            Path dir,
            Set<String> preExisting,
            ToolInvocationContext ctx,
            @Nullable ThinkProcessDocument process) {
        List<Path> newFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !preExisting.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(newFiles::add);
        } catch (IOException e) {
            log.warn("Could not list outputs in {}: {}", dir, e.getMessage());
            return List.of();
        }
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
                        ctx.userId());
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

    /** Glue {@code output} (stdout) and {@code value} (final expression
     *  printed) — omit either when empty so we don't emit dangling
     *  separator lines. */
    static String combine(@Nullable String output, @Nullable String value) {
        String o = output == null ? "" : output.strip();
        String v = value == null ? "" : value.strip();
        if (o.isEmpty() && v.isEmpty()) return "";
        if (o.isEmpty()) return v;
        if (v.isEmpty()) return o;
        // The trailing value gets a separator so the LLM can tell
        // print()-stream apart from the final value of the script.
        return o + "\n" + v;
    }

    private static @Nullable String asString(@Nullable Object v) {
        return v == null ? null : v.toString();
    }
}
