package de.mhus.vance.brain.prompt;

import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Renders a small "Current date" prompt block injected at the tail of
 * the system-prompt section by engines that want the LLM to know today
 * without spending a {@code current_time} tool call.
 *
 * <p>The block is meant to ride as a
 * {@link de.mhus.vance.brain.ai.VanceSystemMessage#dynamic(String)
 * dynamic system message} so the date string doesn't bust the static
 * prompt-cache prefix when it changes (Anthropic) and the engines that
 * use it stay coherent with the cache layout in
 * {@code specification/public/prompt-caching.md} §5.
 *
 * <h2>Granularity</h2>
 *
 * <p>Coarse on purpose — finer than {@code HOUR} would flip the cache
 * suffix every minute. The {@code auto} default picks granularity from
 * the model tier:
 *
 * <ul>
 *   <li>{@link ModelSize#SMALL} → {@link Granularity#DAY} — small
 *       models rarely need wall-clock reasoning, save the tokens.</li>
 *   <li>{@link ModelSize#LARGE} → {@link Granularity#HOUR} — large
 *       models do timed reasoning often enough to justify the cost.</li>
 * </ul>
 *
 * <p>The hour format is {@code "2026-06-24 14h"} — the {@code h}
 * suffix signals that finer granularity is intentionally absent, so
 * the LLM doesn't try to infer minute-level precision.
 */
public final class PromptDateBlock {

    private PromptDateBlock() {}

    public enum Granularity { NONE, DAY, HOUR }

    /** Recipe-param key engines read to drive the date-injection. */
    public static final String RECIPE_PARAM = "promptDateGranularity";

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Resolves the effective granularity from a recipe-param value
     * ({@code auto}/{@code none}/{@code day}/{@code hour}) and the model
     * tier. Unknown / missing / blank values fall back to
     * {@code auto} — date injection is on by default; recipes opt out
     * with an explicit {@code none}.
     */
    public static Granularity resolve(@Nullable String paramValue, @Nullable ModelSize tier) {
        if (paramValue == null || paramValue.isBlank()) {
            return autoFor(tier);
        }
        String v = paramValue.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "none", "off", "false" -> Granularity.NONE;
            case "day", "date" -> Granularity.DAY;
            case "hour" -> Granularity.HOUR;
            case "auto", "true" -> autoFor(tier);
            default -> autoFor(tier);
        };
    }

    private static Granularity autoFor(@Nullable ModelSize tier) {
        return tier == ModelSize.LARGE ? Granularity.HOUR : Granularity.DAY;
    }

    /**
     * Production overload — renders the current date in the given
     * user-facing {@link ZoneId}. This is what engines get via
     * {@link de.mhus.vance.brain.context.PromptDateContextResolver}, so a
     * user in Asia/Kolkata sees their local date, not the pod's.
     */
    public static String render(Granularity granularity, ZoneId zone) {
        return render(granularity, Clock.system(zone));
    }

    /**
     * Renders the prompt block body. Returns the empty string for
     * {@link Granularity#NONE} so callers can short-circuit the
     * {@code VanceSystemMessage.dynamic} wrap.
     */
    public static String render(Granularity granularity, Clock clock) {
        if (granularity == Granularity.NONE) return "";
        ZonedDateTime now = ZonedDateTime.now(clock);
        String body = switch (granularity) {
            case DAY -> now.format(DAY_FORMAT);
            // Hour-precision needs a zone tag — "14h" alone is
            // meaningless to the LLM if the wall clock isn't pinned to
            // a frame of reference. Offset is the cheapest unambiguous
            // form (LLMs read ISO-8601 fluently); we display "Z" as
            // "UTC" because the bare "Z" reads as a typo in prose.
            case HOUR -> now.format(DAY_FORMAT)
                    + " " + String.format(Locale.ROOT, "%02dh", now.getHour())
                    + " " + zoneLabel(now);
            case NONE -> "";
        };
        return "Current date: " + body;
    }

    private static String zoneLabel(ZonedDateTime t) {
        String offset = t.getOffset().getId();
        return "Z".equals(offset) ? "UTC" : offset;
    }

    /**
     * Engine convenience — reads {@link #RECIPE_PARAM} off the process,
     * resolves granularity against the model tier and (if non-NONE)
     * appends a {@link VanceSystemMessage#dynamic(String) dynamic
     * system message} to {@code messages}. No-op when the recipe
     * didn't opt in or when the rendered body is blank.
     */
    public static void appendDynamicMessage(
            List<ChatMessage> messages,
            ThinkProcessDocument process,
            @Nullable ModelSize tier) {
        appendDynamicMessage(messages, process, tier, ZoneId.systemDefault());
    }

    /**
     * Zone-aware variant — renders the date in {@code zone} (the process
     * owner's resolved display timezone). Callers that don't have a user
     * context use the {@link #appendDynamicMessage(List,
     * ThinkProcessDocument, ModelSize) system-default overload}. Prefer
     * routing through
     * {@link de.mhus.vance.brain.context.PromptDateContextResolver}
     * which lifts the session→userId→zone resolution for you.
     */
    public static void appendDynamicMessage(
            List<ChatMessage> messages,
            ThinkProcessDocument process,
            @Nullable ModelSize tier,
            ZoneId zone) {
        Map<String, Object> params = process == null ? null : process.getEngineParams();
        Object raw = params == null ? null : params.get(RECIPE_PARAM);
        String paramValue = raw instanceof String s ? s : null;
        Granularity granularity = resolve(paramValue, tier);
        if (granularity == Granularity.NONE) return;
        String body = render(granularity, zone);
        if (body.isBlank()) return;
        messages.add(VanceSystemMessage.dynamic(body));
    }
}
