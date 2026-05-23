package de.mhus.vance.brain.fook;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.fook.ToolErrorPattern.HealthAction;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Synchronous tool-error triage. Pattern-matches the caught throwable
 * against the rule cascade ({@link ToolErrorPatternResolver}), updates
 * cooldowns + (optionally) the health document directly, and returns
 * the decision so the caller can log / enrich an error reflected to
 * the LLM.
 *
 * <p>Stays out of the way of working calls — invoked only on
 * {@link ToolInvocationContext}-bearing failures.
 *
 * <p>See {@code specification/fook-engine.md} §4.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FookChecker {

    /** Default cooldowns per classification when the pattern doesn't pin one. */
    static final Duration DEFAULT_USER_PERMISSION_COOLDOWN = Duration.ofHours(24);
    static final Duration DEFAULT_USER_INPUT_COOLDOWN = Duration.ofMinutes(5);
    static final Duration DEFAULT_USER_SPECIFIC_COOLDOWN = Duration.ofMinutes(15);
    static final Duration DEFAULT_INTERMITTENT_COOLDOWN = Duration.ofSeconds(30);
    static final Duration DEFAULT_BROKEN_COOLDOWN = Duration.ofMinutes(5);
    static final Duration DEFAULT_UNCLEAR_COOLDOWN = Duration.ofSeconds(30);

    private final ToolErrorPatternResolver resolver;
    private final ToolHealthService healthService;

    /**
     * Triage a failing tool invocation. Side-effects (cooldown,
     * health-doc) are performed against {@link ToolHealthService}.
     */
    public FookCheckResult handle(
            String toolName, Throwable error, ToolInvocationContext ctx) {

        Instant now = Instant.now();
        List<ToolErrorPattern> patterns = resolver.resolve(ctx.tenantId(), ctx.projectId());

        // Pre-compute the cheap matchable features once.
        List<String> exceptionTypes = collectExceptionTypes(error);
        String message = nullToEmpty(error.getMessage());
        @Nullable Integer httpStatus = extractHttpStatus(error);

        ToolErrorPattern matched = null;
        for (ToolErrorPattern p : patterns) {
            if (matches(p, httpStatus, exceptionTypes, message)) {
                matched = p;
                break;
            }
        }
        if (matched == null) {
            log.debug("FookChecker: no pattern matched for tool='{}'", toolName);
            return FookCheckResult.unmatched();
        }

        ToolHealthScope scope = chooseScope(matched.getClassification(), toolName, ctx);
        String scopeId = scopeIdFor(scope, ctx);
        String userKey = userKeyForCooldown(matched.getClassification(), ctx);
        String signature = matched.getSignature();

        // Already cooling down? Don't refresh hits, don't write status —
        // the LLM will just see the same backend error reflected.
        Optional<ToolHealthCooldown> active = healthService.lookupActiveCooldown(
                ctx.tenantId(), scope, scopeId, toolName, signature, userKey, now);
        if (active.isPresent()) {
            log.debug("FookChecker: cooldown still active for tool='{}' sig='{}' user='{}' until {}",
                    toolName, signature, userKey, active.get().getNextSpawnAllowedAt());
            return new FookCheckResult(
                    matched.getId(), matched.getClassification(), signature,
                    true, false, matched.getNote());
        }

        Duration cooldown = resolveCooldown(matched);
        Instant expectedRecovery = cooldown == null
                ? null
                : now.plus(cooldown);

        // Direct health-doc write when the pattern asks for it.
        boolean wroteHealth = false;
        switch (matched.getHealthAction()) {
            case MARK_UNAVAILABLE -> {
                healthService.markUnavailable(
                        ctx.tenantId(), scope, scopeId, toolName,
                        matched.getClassification(), expectedRecovery,
                        matched.getNote(), "fook-checker");
                wroteHealth = true;
            }
            case MARK_DEGRADED -> {
                healthService.markDegraded(
                        ctx.tenantId(), scope, scopeId, toolName,
                        matched.getClassification(), expectedRecovery,
                        matched.getNote(), "fook-checker");
                wroteHealth = true;
            }
            case NONE -> { /* cooldown-only */ }
        }

        // Set / refresh the cooldown.
        if (cooldown != null) {
            healthService.setCooldown(
                    ctx.tenantId(), scope, scopeId, toolName,
                    signature, userKey,
                    matched.getClassification(), cooldown,
                    matched.getNote());
        }

        return new FookCheckResult(
                matched.getId(), matched.getClassification(), signature,
                false, wroteHealth, matched.getNote());
    }

    // ──────────────────────────────── matching

    static boolean matches(
            ToolErrorPattern p,
            @Nullable Integer httpStatus,
            List<String> exceptionTypes,
            String message) {
        if (p.getHttpStatus() != null) {
            if (httpStatus == null || !p.getHttpStatus().equals(httpStatus)) return false;
        }
        if (p.getHttpStatusRange() != null && p.getHttpStatusRange().length == 2) {
            if (httpStatus == null) return false;
            int lo = p.getHttpStatusRange()[0];
            int hi = p.getHttpStatusRange()[1];
            if (httpStatus < lo || httpStatus > hi) return false;
        }
        if (p.getExceptionTypes() != null && !p.getExceptionTypes().isEmpty()) {
            boolean any = false;
            for (String type : p.getExceptionTypes()) {
                if (exceptionTypes.contains(type)) { any = true; break; }
            }
            if (!any) return false;
        }
        if (p.getBodyContains() != null && !p.getBodyContains().isEmpty()) {
            boolean any = false;
            String lower = message.toLowerCase(Locale.ROOT);
            for (String needle : p.getBodyContains()) {
                if (lower.contains(needle.toLowerCase(Locale.ROOT))) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    private static List<String> collectExceptionTypes(Throwable t) {
        List<String> out = new java.util.ArrayList<>();
        Throwable cur = t;
        int guard = 16;
        while (cur != null && guard-- > 0) {
            out.add(cur.getClass().getName());
            cur = cur.getCause();
        }
        return out;
    }

    private static @Nullable Integer extractHttpStatus(Throwable t) {
        // Best-effort regex on the throwable's getMessage chain — many
        // HTTP clients in the codebase wrap server responses as
        // "... HTTP 4xx ..." in the exception message. When a real
        // typed HttpStatusException is introduced, prefer that path.
        Throwable cur = t;
        int guard = 16;
        while (cur != null && guard-- > 0) {
            String m = cur.getMessage();
            if (m != null) {
                Integer s = pickStatus(m);
                if (s != null) return s;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static @Nullable Integer pickStatus(String msg) {
        // Match patterns like "HTTP 401", "status: 503", "(429)", "code=403".
        java.util.regex.Matcher m = HTTP_STATUS_PATTERN.matcher(msg);
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 100 && v <= 599) return v;
            } catch (NumberFormatException ignored) { /* keep looking */ }
        }
        return null;
    }

    private static final java.util.regex.Pattern HTTP_STATUS_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?:\\bHTTP\\s+|\\bstatus[:=]\\s*|\\bcode[:=]\\s*|[(\\s])(\\d{3})\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    // ──────────────────────────────── scope + cooldown helpers

    private static ToolHealthScope chooseScope(
            ToolHealthClassification classification,
            String toolName,
            ToolInvocationContext ctx) {
        // Client-side tools are always session-scoped — their backend
        // is the foot/web connection.
        if (toolName.startsWith("client_") && ctx.sessionId() != null) {
            return ToolHealthScope.SESSION;
        }
        return switch (classification) {
            case USER_PERMISSION, USER_SPECIFIC_TECHNICAL, USER_INPUT -> ctx.userId() != null
                    ? ToolHealthScope.USER
                    : fallbackScope(ctx);
            case TECHNICALLY_BROKEN, INTERMITTENT, WORKING, UNCLEAR -> fallbackScope(ctx);
        };
    }

    private static ToolHealthScope fallbackScope(ToolInvocationContext ctx) {
        if (ctx.projectId() != null && !ctx.projectId().isBlank()) {
            return ToolHealthScope.PROJECT;
        }
        return ToolHealthScope.TENANT;
    }

    private static String scopeIdFor(ToolHealthScope scope, ToolInvocationContext ctx) {
        return switch (scope) {
            case SESSION -> nullToEmpty(ctx.sessionId());
            case USER -> nullToEmpty(ctx.userId());
            case PROJECT -> nullToEmpty(ctx.projectId());
            case TENANT -> nullToEmpty(ctx.tenantId());
            case GLOBAL -> "";
        };
    }

    private static @Nullable String userKeyForCooldown(
            ToolHealthClassification classification, ToolInvocationContext ctx) {
        return switch (classification) {
            case USER_PERMISSION, USER_SPECIFIC_TECHNICAL, USER_INPUT -> ctx.userId();
            default -> null;
        };
    }

    private static @Nullable Duration resolveCooldown(ToolErrorPattern p) {
        Duration explicit = p.getCooldown();
        if (explicit != null && explicit != ToolErrorPattern.COOLDOWN_FROM_RETRY_AFTER) {
            return explicit;
        }
        if (explicit == ToolErrorPattern.COOLDOWN_FROM_RETRY_AFTER) {
            // No retry-after header surface today — fall through to the
            // intermittent default. When a typed HTTP error is added,
            // read the header here and prefer it.
            return DEFAULT_INTERMITTENT_COOLDOWN;
        }
        return switch (p.getClassification()) {
            case USER_PERMISSION -> DEFAULT_USER_PERMISSION_COOLDOWN;
            case USER_INPUT -> DEFAULT_USER_INPUT_COOLDOWN;
            case USER_SPECIFIC_TECHNICAL -> DEFAULT_USER_SPECIFIC_COOLDOWN;
            case INTERMITTENT -> DEFAULT_INTERMITTENT_COOLDOWN;
            case TECHNICALLY_BROKEN -> DEFAULT_BROKEN_COOLDOWN;
            case UNCLEAR -> DEFAULT_UNCLEAR_COOLDOWN;
            case WORKING -> null;
        };
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
