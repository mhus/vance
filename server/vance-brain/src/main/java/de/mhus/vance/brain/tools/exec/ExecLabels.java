package de.mhus.vance.brain.tools.exec;

/**
 * Reserved label keys and well-known values for {@link ExecJob#labels()}
 * and {@link de.mhus.vance.brain.execution.ExecutionRegistryEntry#labels()}.
 *
 * <p>Convention namespace: {@code cortex.*}. The prefix is historical
 * (grew out of Script-Cortex run-management) and stays even for non-
 * Cortex spawners — it just identifies the well-known schema.
 *
 * <p>Reserved keys (system-set):
 * <ul>
 *   <li>{@link #KEY_SOURCE} — {@code cortex} / {@code llm-tool} /
 *       {@code workflow} / {@code manual}</li>
 *   <li>{@link #KEY_LANGUAGE} — {@code python} / {@code js} /
 *       {@code shell}</li>
 *   <li>{@link #KEY_RUN_KIND} — {@code script} / {@code install} /
 *       {@code uninstall} / {@code validate} / {@code shell}</li>
 *   <li>{@link #KEY_DOCUMENT} — full document path for runs that target
 *       a specific {@code DocumentDocument}</li>
 * </ul>
 *
 * <p>User-/tool-set keys may use the {@code meta.*} namespace or any
 * unprefixed key; reserved keys are owned by the spawn-site.
 *
 * <p><b>Abgrenzung zu Micrometer-Tags:</b> Diese Labels sind reine
 * Per-Instance-Metadaten in einer In-Memory-Map und werden nie an
 * Micrometer/Prometheus weitergereicht. Hohe Kardinalität (z.B.
 * Doc-Paths) ist hier unproblematisch — die TSDB-Regel aus
 * {@code CLAUDE.md} §"Metriken" gilt nicht.
 */
public final class ExecLabels {

    public static final String KEY_SOURCE = "cortex.source";
    public static final String KEY_LANGUAGE = "cortex.language";
    public static final String KEY_RUN_KIND = "cortex.runKind";
    public static final String KEY_DOCUMENT = "cortex.document";
    /**
     * Caller-allocated run id stamped on script-execution spawns so the
     * {@code SCRIPT_RUN} JWT validator can look up the registry entry
     * by claim (see {@link
     * de.mhus.vance.brain.access.ScriptRunAuthService}). Distinct from
     * the {@code ExecJob#id()} which is opaque to issuers because it is
     * generated inside {@code ExecManager.submit(...)}.
     */
    public static final String KEY_RUN_ID = "cortex.runId";

    public static final String SOURCE_CORTEX = "cortex";
    public static final String SOURCE_LLM_TOOL = "llm-tool";
    public static final String SOURCE_WORKFLOW = "workflow";
    public static final String SOURCE_MANUAL = "manual";

    public static final String LANG_PYTHON = "python";
    public static final String LANG_JS = "js";
    public static final String LANG_SHELL = "shell";

    public static final String RUN_KIND_SCRIPT = "script";
    public static final String RUN_KIND_SHELL = "shell";
    public static final String RUN_KIND_INSTALL = "install";
    public static final String RUN_KIND_UNINSTALL = "uninstall";
    public static final String RUN_KIND_VALIDATE = "validate";

    private ExecLabels() {}
}
