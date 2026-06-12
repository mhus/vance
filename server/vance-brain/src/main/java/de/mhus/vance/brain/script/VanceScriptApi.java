package de.mhus.vance.brain.script;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host-API surface exposed to brain-side JavaScript as the {@code vance}
 * binding. Reachable members are annotated {@link HostAccess.Export};
 * the script context is configured to allow nothing else.
 *
 * <p>Identity (tenant/project/session/process) comes from the bound
 * {@link ContextToolsApi}, never from script-supplied parameters. A
 * script cannot escape its scope by passing a different tenant or
 * project to a tool call.
 */
public final class VanceScriptApi {

    private static final Logger LOG = LoggerFactory.getLogger("vance.script");

    /**
     * Out-of-band hook that lets callers tap every {@code vance.log.*}
     * call from inside the script context. Used by Script Cortex to
     * surface server-side log lines in the Execute dialog's Output
     * pane alongside {@code console.*} output — without it, users
     * who reach for {@code vance.log.info(...)} see nothing.
     *
     * <p>The hook is {@link InheritableThreadLocal} on purpose: the
     * GraalJS watchdog runs the eval on a child thread of the caller,
     * and ThreadLocal isn't inherited automatically. Inheritable copy
     * propagates the active tee into the watchdog thread on creation.
     *
     * <p>Caller contract: set right before the script runs, clear in
     * a finally block. {@link ScriptLog} reads the value on every
     * log call and pushes a {@code (stream, formattedLine)} tuple
     * when present. The SLF4J log is unaffected.
     */
    private static final InheritableThreadLocal<BiConsumer<String, String>>
            ACTIVE_LOG_TEE = new InheritableThreadLocal<>();

    public static void setActiveLogTee(@Nullable BiConsumer<String, String> tee) {
        if (tee == null) ACTIVE_LOG_TEE.remove();
        else ACTIVE_LOG_TEE.set(tee);
    }

    public static void clearActiveLogTee() {
        ACTIVE_LOG_TEE.remove();
    }

    @HostAccess.Export
    public final ScriptToolsApi tools;

    @HostAccess.Export
    public final ScriptContextView context;

    @HostAccess.Export
    public final ScriptLog log;

    @HostAccess.Export
    public final ScriptProcessApi process;

    /**
     * Document-access surface exposed as {@code vance.documents}. Resolves
     * scope from the bound {@link ContextToolsApi}; scripts cannot reach
     * outside their tenant/project. {@code null} when no
     * {@link DocumentService} was wired into the constructor — legacy
     * call-sites that pre-date Phase 3 still build the API without it
     * (trigger-scoped scripts, unit-test stubs) and would NPE on first
     * access via {@code vance.documents.*} with a clear
     * {@link ScriptHostException}.
     */
    @HostAccess.Export
    public final @Nullable ScriptDocumentApi documents;

    public VanceScriptApi(ContextToolsApi toolsApi) {
        this(toolsApi, null, Set.of(), null);
    }

    public VanceScriptApi(ContextToolsApi toolsApi, @Nullable String recipeName) {
        this(toolsApi, recipeName, Set.of(), null);
    }

    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames) {
        this(toolsApi, recipeName, deniedToolNames, null);
    }

    /**
     * Full constructor. {@code deniedToolNames} is the set of tools
     * that {@link ScriptToolsApi#call} refuses outright — typically the
     * spawn-tool set in trigger-scoped runs (see
     * {@link de.mhus.vance.brain.action.SpawnToolRegistry} and
     * {@code planning/trigger-actions.md} §8).
     *
     * <p>{@code documentService} enables the {@code vance.documents.*}
     * binding. Pass {@code null} for scripts that mustn't touch documents
     * (legacy trigger-scoped runs); accesses then throw a
     * {@link ScriptHostException} with a clear message instead of NPE.
     */
    public VanceScriptApi(ContextToolsApi toolsApi,
                          @Nullable String recipeName,
                          Set<String> deniedToolNames,
                          @Nullable DocumentService documentService) {
        this.tools = new ScriptToolsApi(toolsApi, deniedToolNames);
        this.context = new ScriptContextView(toolsApi.scope(), recipeName);
        this.log = new ScriptLog(toolsApi.scope());
        this.process = new ScriptProcessApi(this);
        this.documents = documentService == null
                ? null
                : new ScriptDocumentApi(documentService, toolsApi.scope());
    }

    /** Tool-dispatch surface exposed as {@code vance.tools}. */
    public static final class ScriptToolsApi {

        private final ContextToolsApi delegate;
        private final Set<String> deniedToolNames;

        ScriptToolsApi(ContextToolsApi delegate, Set<String> deniedToolNames) {
            this.delegate = delegate;
            this.deniedToolNames = deniedToolNames == null ? Set.of() : Set.copyOf(deniedToolNames);
        }

        @HostAccess.Export
        public Map<String, Object> call(String name, @Nullable Map<String, Object> params) {
            if (deniedToolNames.contains(name)) {
                throw new ScriptHostException(
                        "Tool '" + name + "' not allowed in trigger-scoped script — "
                                + "wrap in a workflow if you need it",
                        null);
            }
            try {
                return delegate.invoke(name, params == null ? Map.of() : params);
            } catch (ToolException e) {
                throw new ScriptHostException(e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new ScriptHostException(
                        "Tool '" + name + "' failed: " + e.getMessage(), e);
            }
        }
    }

    /** Read-only scope info exposed as {@code vance.context}. */
    public static final class ScriptContextView {

        @HostAccess.Export
        public final String tenantId;

        @HostAccess.Export
        public final @Nullable String projectId;

        @HostAccess.Export
        public final @Nullable String sessionId;

        @HostAccess.Export
        public final @Nullable String processId;

        @HostAccess.Export
        public final @Nullable String userId;

        /** Recipe name that spawned the running process — exposed so
         *  scripts (e.g. Hactar-generated orchestrators) can
         *  branch on their invocation context. {@code null} when the
         *  caller didn't supply a recipe (direct engine spawns,
         *  legacy 5-/6-arg {@code ScriptRequest} constructors). */
        @HostAccess.Export
        public final @Nullable String recipe;

        ScriptContextView(ToolInvocationContext scope, @Nullable String recipeName) {
            this.tenantId = scope.tenantId();
            this.projectId = scope.projectId();
            this.sessionId = scope.sessionId();
            this.processId = scope.processId();
            this.userId = scope.userId();
            this.recipe = recipeName;
        }
    }

    /** Structured logger exposed as {@code vance.log}. */
    public static final class ScriptLog {

        private final ToolInvocationContext scope;

        ScriptLog(ToolInvocationContext scope) {
            this.scope = scope;
        }

        @HostAccess.Export
        public void info(String message, @Nullable Map<String, Object> fields) {
            LOG.info("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
            tee("info", message, fields);
        }

        @HostAccess.Export
        public void warn(String message, @Nullable Map<String, Object> fields) {
            LOG.warn("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
            tee("warn", message, fields);
        }

        @HostAccess.Export
        public void error(String message, @Nullable Map<String, Object> fields) {
            LOG.error("[script] tenant={} project={} process={} {} {}",
                    scope.tenantId(), scope.projectId(), scope.processId(),
                    message, fields == null ? Map.of() : fields);
            tee("error", message, fields);
        }

        private static void tee(String stream, String message,
                                @Nullable Map<String, Object> fields) {
            BiConsumer<String, String> hook = ACTIVE_LOG_TEE.get();
            if (hook == null) return;
            String line = (fields == null || fields.isEmpty())
                    ? message : message + " " + fields;
            try {
                hook.accept(stream, line);
            } catch (RuntimeException ignored) {
                // Hook failures must never leak back into the script.
            }
        }
    }

    /**
     * Process surface exposed as {@code vance.process}. v1 only forwards
     * to the {@code process_create} tool — direct event-send is not
     * routed because no matching tool exists yet.
     */
    public static final class ScriptProcessApi {

        private final VanceScriptApi parent;

        ScriptProcessApi(VanceScriptApi parent) {
            this.parent = parent;
        }

        @HostAccess.Export
        public Map<String, Object> spawn(Map<String, Object> params) {
            return parent.tools.call("process_create", params);
        }
    }

    /**
     * Document surface exposed as {@code vance.documents}. All operations
     * scope to the run's tenant + project; cross-project access is
     * impossible because the path is the only script-supplied input.
     *
     * <p>Paths use the same convention as {@link DocumentDocument#getPath()}
     * (no leading slash, forward-slash-separated). Writes to the trash
     * folder ({@link DocumentService#TRASH_FOLDER_PREFIX}) are refused.
     */
    public static final class ScriptDocumentApi {

        private final DocumentService documentService;
        private final ToolInvocationContext scope;

        ScriptDocumentApi(DocumentService documentService, ToolInvocationContext scope) {
            this.documentService = documentService;
            this.scope = scope;
        }

        /**
         * Read a document as UTF-8 text. Throws {@link ScriptHostException}
         * when no such document exists — JS catches it as a normal Error.
         */
        @HostAccess.Export
        public String read(String path) {
            DocumentDocument doc = requireDoc(path);
            return documentService.readContent(doc);
        }

        /**
         * Idempotent write — creates the document if it doesn't exist,
         * updates content if it does. {@code title} and {@code tags} on
         * an existing document stay untouched.
         */
        @HostAccess.Export
        public void write(String path, String content) {
            requireProject();
            requirePath(path);
            if (content == null) {
                throw new ScriptHostException(
                        "vance.documents.write: content must not be null", null);
            }
            if (path.startsWith(DocumentService.TRASH_FOLDER_PREFIX)) {
                throw new ScriptHostException(
                        "vance.documents.write: cannot write under '"
                                + DocumentService.TRASH_FOLDER_PREFIX + "'", null);
            }
            documentService.upsertText(
                    scope.tenantId(), scope.projectId(),
                    path, null, null, content, scope.userId());
        }

        @HostAccess.Export
        public boolean exists(String path) {
            requireProject();
            requirePath(path);
            return documentService.findByPath(
                    scope.tenantId(), scope.projectId(), path).isPresent();
        }

        /**
         * Soft-delete: moves the document to {@link
         * DocumentService#TRASH_FOLDER_PREFIX}. Idempotent — deleting a
         * non-existing document is a no-op (returns {@code false}).
         */
        @HostAccess.Export
        public boolean delete(String path) {
            requireProject();
            requirePath(path);
            return documentService.findByPath(
                            scope.tenantId(), scope.projectId(), path)
                    .map(doc -> {
                        documentService.trash(doc.getId());
                        return true;
                    })
                    .orElse(false);
        }

        /**
         * List documents under an optional path prefix. Returns
         * lightweight summary maps (id, path, name, kind, mimeType, size,
         * tags, createdAt, updatedAt) so JS doesn't see internal storage
         * fields. {@code prefix} is matched as {@code startsWith}; pass
         * {@code null} or empty for project-wide.
         *
         * <p>Trash folder is excluded automatically (consistent with
         * {@link DocumentService#listByProjectPaged}).
         */
        @HostAccess.Export
        public List<Map<String, Object>> list(@Nullable String prefix) {
            requireProject();
            List<Map<String, Object>> out = new ArrayList<>();
            // Page through up to 200 at a time — caller can pass a more
            // specific prefix if they hit the cap in practice.
            documentService.listByProjectPaged(
                            scope.tenantId(), scope.projectId(), 0, 200, prefix)
                    .forEach(doc -> out.add(toSummary(doc)));
            return out;
        }

        /**
         * Metadata snapshot for the given path. Same shape as the entries
         * returned by {@link #list(String)}; throws
         * {@link ScriptHostException} when the document doesn't exist.
         */
        @HostAccess.Export
        public Map<String, Object> meta(String path) {
            return toSummary(requireDoc(path));
        }

        private DocumentDocument requireDoc(String path) {
            requireProject();
            requirePath(path);
            return documentService.findByPath(
                            scope.tenantId(), scope.projectId(), path)
                    .orElseThrow(() -> new ScriptHostException(
                            "vance.documents: not found '" + path + "'", null));
        }

        private void requireProject() {
            if (scope.projectId() == null || scope.projectId().isBlank()) {
                throw new ScriptHostException(
                        "vance.documents requires a project-scoped run", null);
            }
        }

        private static void requirePath(String path) {
            if (path == null || path.isBlank()) {
                throw new ScriptHostException(
                        "vance.documents: path must not be empty", null);
            }
        }

        private static Map<String, Object> toSummary(DocumentDocument doc) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", doc.getId());
            m.put("path", doc.getPath());
            m.put("name", doc.getName());
            m.put("title", doc.getTitle());
            m.put("kind", doc.getKind());
            m.put("mimeType", doc.getMimeType());
            m.put("size", doc.getSize());
            m.put("tags", doc.getTags() == null ? List.of() : List.copyOf(doc.getTags()));
            m.put("createdAt", doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
            m.put("version", doc.getVersion());
            return m;
        }
    }

    /** Host-side exception surfaced to JS as a regular Error. */
    public static final class ScriptHostException extends RuntimeException {
        public ScriptHostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
