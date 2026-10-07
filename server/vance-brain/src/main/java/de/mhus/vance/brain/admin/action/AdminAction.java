package de.mhus.vance.brain.admin.action;

import de.mhus.vance.api.admin.AdminActionRunResultDto;

/**
 * One operator action — a command the Settings page's "Aktionen" tab
 * lists and runs (catalog refresh, model discovery, health checks, …).
 * The tab is deliberately generic: it renders whatever the registry
 * serves, so a new action is a Spring bean implementing this interface
 * and nothing else — no client release, no host code.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>{@link #id()} is stable — it names the REST run path and may
 *       appear in operator tooling. Kebab-case.</li>
 *   <li>{@link #title()} / {@link #description()} are localized
 *       {@code Map<lang, text>} the same way wizard texts are; the
 *       client resolves with the universal {@code en} fallback.</li>
 *   <li>{@link #run} executes synchronously and returns the generic
 *       result shape. An action that <em>finds problems</em> (a failed
 *       model ping) returns ok=false — findings are results, not
 *       transport errors; only execution breakdowns throw.</li>
 * </ul>
 *
 * <p>Implementation note: implementations are stateless Spring beans.
 * The controller owns ADMIN enforcement — actions never check
 * permissions themselves, so an action cannot accidentally lower the
 * bar of the surface that lists it.
 */
public interface AdminAction {

    /** Stable, kebab-case action id ({@code admin/actions/{id}}). */
    String id();

    /** Localized title, {@code Map<lang, text>} with {@code en} fallback. */
    java.util.Map<String, String> title();

    /** Localized description — what the action does, when to run it. */
    java.util.Map<String, String> description();

    /**
     * Whether the action can run against a plain project, or only in
     * the tenant scope. Actions whose work is pod- or tenant-global by
     * nature (catalog cache, whole-tenant discovery) stay
     * {@link AdminActionScope#TENANT_ONLY} instead of pretending a
     * project run would mean something else.
     */
    AdminActionScope scope();

    /**
     * Executes the action in the given context. Long individual work
     * items (model pings) should bound their parallelism and keep
     * per-item durations — the result carries them per row.
     *
     * @throws RuntimeException on execution breakdown; findings go in
     *     the result, never as exceptions
     */
    AdminActionRunResultDto run(AdminActionContext context);
}
