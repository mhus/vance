package de.mhus.vance.brain.hooks;

import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured logger exposed as {@code log.*}. Entries land in
 * {@code vance.hooks.run} at the requested level with the
 * correlation-id and hook-name prepended — they do <em>not</em> bubble
 * into the process log (a hook is not part of a process turn).
 */
public final class HookLog {

    private static final Logger LOG = LoggerFactory.getLogger("vance.hooks.run");

    private final HookContext ctx;

    public HookLog(HookContext ctx) {
        this.ctx = ctx;
    }

    @HostAccess.Export
    public void info(String message) {
        info(message, null);
    }

    @HostAccess.Export
    public void info(String message, @Nullable Map<String, Object> fields) {
        LOG.info("[hook:{}:{}] correlation={} {} {}",
                ctx.event().wireName(), ctx.hookName(), ctx.correlationId(),
                message, fields == null ? Map.of() : fields);
    }

    @HostAccess.Export
    public void warn(String message) {
        warn(message, null);
    }

    @HostAccess.Export
    public void warn(String message, @Nullable Map<String, Object> fields) {
        LOG.warn("[hook:{}:{}] correlation={} {} {}",
                ctx.event().wireName(), ctx.hookName(), ctx.correlationId(),
                message, fields == null ? Map.of() : fields);
    }

    @HostAccess.Export
    public void error(String message) {
        error(message, null);
    }

    @HostAccess.Export
    public void error(String message, @Nullable Map<String, Object> fields) {
        LOG.error("[hook:{}:{}] correlation={} {} {}",
                ctx.event().wireName(), ctx.hookName(), ctx.correlationId(),
                message, fields == null ? Map.of() : fields);
    }
}
