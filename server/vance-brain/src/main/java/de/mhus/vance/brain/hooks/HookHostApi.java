package de.mhus.vance.brain.hooks;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;

/**
 * Aggregated host-API view a hook sees. The fields are exposed as
 * top-level JS bindings by {@link JsHookRunner} — the script writes
 * {@code event.process.name}, {@code context.tenantId},
 * {@code http.post(...)} directly. Each sub-API is a separate POJO
 * with {@link HostAccess.Export} members.
 *
 * <p>Same surface is consumed by {@link LlmHookRunner} when it
 * executes the structured action list the model produced: every
 * {@code kind} maps to one of the sub-APIs ({@code http.post → http},
 * {@code inbox.create → inbox}, {@code log.info → log}). The model
 * never sees the surface directly — only the action verbs.
 *
 * <p>Read sub-APIs ({@link #event}, {@link #context}) are immutable
 * views; write sub-APIs ({@link #http}, {@link #inbox}, {@link #log})
 * delegate to scope-aware service beans bound at construction time.
 */
public final class HookHostApi {

    /** Read-only event payload (event-specific shape). */
    public final Map<String, @Nullable Object> event;

    /** Read-only per-run scope. */
    public final HookContextView context;

    /** HTTP write channel (POST / PUT / GET against whitelisted hosts). */
    public final HookHttpClient http;

    /** Inbox write channel. */
    public final HookInboxClient inbox;

    /** Structured logging into the hook-run log (not the process log). */
    public final HookLog log;

    /** Hactar workflow write channel — {@code workflows.start(name, params)}. */
    public final HookWorkflowClient workflows;

    public HookHostApi(
            HookContext ctx,
            Map<String, @Nullable Object> event,
            HookHttpClient http,
            HookInboxClient inbox,
            HookLog log,
            HookWorkflowClient workflows,
            HookSettingsView settings) {
        // Wrap the payload defensively: the host-side trigger emitter
        // hands over an internally-owned map and we don't want the
        // script (or anyone) mutating it.
        this.event = Collections.unmodifiableMap(event);
        this.context = new HookContextView(ctx, settings);
        this.http = http;
        this.inbox = inbox;
        this.log = log;
        this.workflows = workflows;
    }

    /** Read-only scope info exposed as {@code context.*}. */
    public static final class HookContextView {

        @HostAccess.Export public final String tenantId;
        @HostAccess.Export public final String projectId;
        @HostAccess.Export public final String eventName;
        @HostAccess.Export public final String hookName;
        @HostAccess.Export public final String correlationId;
        @HostAccess.Export public final String firedAt;

        private final HookSettingsView settings;

        HookContextView(HookContext ctx, HookSettingsView settings) {
            this.tenantId = ctx.tenantId();
            this.projectId = ctx.projectId();
            this.eventName = ctx.event().wireName();
            this.hookName = ctx.hookName();
            this.correlationId = ctx.correlationId();
            this.firedAt = ctx.firedAt() == null ? Instant.now().toString() : ctx.firedAt().toString();
            this.settings = settings;
        }

        /**
         * Cascade setting read — Project → {@code _vance}. Returns
         * {@code null} when the key is unset; the script handles the
         * fallback. Password-typed settings are returned as plaintext
         * (the cascade-resolver decrypts) — the script is the caller
         * of an outbound channel, so it's the legitimate consumer.
         */
        @HostAccess.Export
        public @Nullable String setting(String key) {
            return settings.read(key);
        }
    }
}
