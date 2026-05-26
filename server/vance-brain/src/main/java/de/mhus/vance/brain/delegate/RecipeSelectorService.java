package de.mhus.vance.brain.delegate;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Picks a project recipe for a free-text task description. Trigger-
 * gated: a deterministic pre-check (recipe-name word-boundary + trigger-
 * keyword substring) runs first. The LLM call only fires when multiple
 * recipes match the same trigger and need disambiguation. Without any
 * trigger the selector returns {@code NONE} immediately — caller falls
 * back to its default recipe (typically {@code default} → ford).
 *
 * <p>See {@code specification/recipe-routing.md} for the full design.
 *
 * <h2>Pre-check stages</h2>
 * <ol>
 *   <li><b>Recipe-name word-boundary match.</b> Any recipe name that
 *       appears as a word in the goal text is a direct match — no LLM,
 *       no ambiguity. Longest-match wins when two recipe names overlap
 *       (e.g. {@code analyze} vs {@code deep-analyze}).</li>
 *   <li><b>Trigger-keyword substring match.</b> Recipes declare phrases
 *       via {@code triggers.keywords:} in their YAML. Substring match
 *       on the lower-cased goal. Multiple matches → only-matched
 *       candidates feed the LLM for disambiguation; single match →
 *       deterministic.</li>
 *   <li><b>No trigger.</b> Return {@code NONE} with a rationale.
 *       The caller spawns its default recipe (no Slart fallback —
 *       see {@code routing.fallback.recipe} setting).</li>
 * </ol>
 *
 * <h2>Why deterministic comes first</h2>
 *
 * Magical LLM-routing on every empty-recipe spawn was the source of
 * the worst routing failures we saw in field testing: the selector
 * picked specialist engines (Marvin, Vogon) for trivial tasks, or
 * the opposite. Forcing the user to <em>opt in</em> by naming the
 * engine / recipe / category keyword turns the selector into a clear
 * escalation gate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeSelectorService {

    /** Selector identity used for tracing / model-alias namespacing. */
    private static final String SELECTOR_NAME = "recipe-selector";

    private static final String SYSTEM_PROMPT = """
            You are the recipe selector for the Vance multi-engine
            think-system. The caller has already determined — via a
            deterministic keyword pre-check — that the user's goal
            triggers one of several candidate recipes. Your job is to
            pick the single best fit from the short candidate list,
            or report that none of them actually fits.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema (every field mandatory, even when null):
                {
                  "decision":  "MATCH" | "NONE",
                  "recipe":    "<recipe-name from the candidates>" | null,
                  "rationale": "<1-2 sentences: why this recipe, or
                                why nothing matched>"
                }

            Decision rules:
            - "MATCH" when one of the listed candidates fits the
              user's intent. The recipe-name MUST appear VERBATIM in
              the candidate list — no inventing names.
            - "NONE" when the keyword-matched candidates all turn out
              to be wrong fits on closer inspection. The caller will
              fall back to a configured fallback recipe
              (routing.fallback.recipe — typically Hactar, which can
              generate a script to handle arbitrary goals).

            Selection guidance:
            - Match on the user's INTENT, not surface words. The
              keyword that triggered the candidates is a hint, not a
              decision.
            - Prefer specific recipes over generic orchestrators when
              both are candidates. A purpose-built recipe is almost
              always a better fit than a bare Marvin/Vogon shell
              with no sub-recipes.
            - "NONE" is a respectable answer when none of the
              candidates is a real fit — the fallback recipe is built
              for that case.
            """;

    private final ObjectMapper objectMapper;
    private final RecipeLoader recipeLoader;
    private final SettingService settingService;
    private final AiModelResolver aiModelResolver;
    private final AiModelService aiModelService;

    /**
     * Runs the selector. Returns a {@link Result} describing what to
     * do — never throws on a bad LLM response (returns
     * {@link Result#none(String)} with a diagnostic instead) so the
     * caller can decide how to fall back.
     */
    public Result select(ThinkProcessDocument caller, String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return Result.noneWithoutTrigger("empty task description");
        }
        List<ResolvedRecipe> inventory = listRecipesForRouting(caller);
        if (inventory.isEmpty()) {
            return Result.noneWithoutTrigger(
                    "no recipes available in tenant/project — "
                            + "caller falls back to default recipe");
        }

        String lower = taskDescription.toLowerCase(Locale.ROOT);

        // Stage 1: deterministic recipe-name match.
        ResolvedRecipe byName = matchByRecipeName(inventory, lower);
        if (byName != null) {
            log.debug("RecipeSelector: deterministic recipe-name match recipe='{}' engine='{}'",
                    byName.name(), byName.engine());
            return Result.match(byName.name(), byName.engine(),
                    "Recipe name '" + byName.name()
                            + "' detected in goal (deterministic, no LLM call).");
        }

        // Stage 2: trigger-keyword pre-filter.
        List<ResolvedRecipe> triggered = matchByTriggerKeywords(inventory, lower);
        if (triggered.isEmpty()) {
            return Result.noneWithoutTrigger(
                    "no trigger detected in goal — caller falls back to "
                            + "default recipe (no LLM call made).");
        }
        if (triggered.size() == 1) {
            ResolvedRecipe r = triggered.get(0);
            log.debug("RecipeSelector: single trigger-keyword match recipe='{}' engine='{}'",
                    r.name(), r.engine());
            return Result.match(r.name(), r.engine(),
                    "Trigger keyword in recipe '" + r.name()
                            + "' matched the goal (deterministic, no LLM call).");
        }

        // Stage 3: multiple candidates → ask the LLM for disambiguation.
        log.debug("RecipeSelector: {} candidates triggered, running LLM disambiguation",
                triggered.size());
        return runLlmDisambiguation(caller, triggered, taskDescription);
    }

    // ──────────────────── deterministic matchers ────────────────────

    /**
     * Walks the full inventory and returns the recipe whose name
     * appears as a stand-alone word in the goal text. Word boundary
     * counts {@code a-z 0-9 _ -} as word characters so hyphenated
     * names like {@code quick-lookup} stay intact. Longest match
     * wins to avoid {@code analyze} stealing from {@code deep-analyze}.
     */
    private static @Nullable ResolvedRecipe matchByRecipeName(
            List<ResolvedRecipe> inventory, String lowerGoal) {
        ResolvedRecipe best = null;
        int bestLen = 0;
        for (ResolvedRecipe r : inventory) {
            String n = r.name().toLowerCase(Locale.ROOT);
            if (n.isEmpty() || n.length() <= bestLen) continue;
            if (containsAsWord(lowerGoal, n)) {
                best = r;
                bestLen = n.length();
            }
        }
        return best;
    }

    /**
     * Returns every recipe whose {@code triggerKeywords} contains a
     * substring found in {@code lowerGoal}. Trigger keywords are
     * already lower-cased at parse time, so the comparison is a plain
     * {@link String#contains}. Order matches the inventory order,
     * which itself reflects the cascade (project → tenant → bundled).
     */
    private static List<ResolvedRecipe> matchByTriggerKeywords(
            List<ResolvedRecipe> inventory, String lowerGoal) {
        List<ResolvedRecipe> hits = new ArrayList<>();
        for (ResolvedRecipe r : inventory) {
            List<String> kws = r.triggerKeywords();
            if (kws == null || kws.isEmpty()) continue;
            for (String kw : kws) {
                if (lowerGoal.contains(kw)) {
                    hits.add(r);
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Word-boundary {@link String#contains}. We hand-roll instead of
     * using a regex because we walk the inventory for every spawn
     * and want the allocation profile flat.
     */
    static boolean containsAsWord(String haystack, String needle) {
        if (needle.isEmpty() || needle.length() > haystack.length()) return false;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            char before = idx == 0 ? ' ' : haystack.charAt(idx - 1);
            int after = idx + needle.length();
            char afterC = after >= haystack.length() ? ' ' : haystack.charAt(after);
            if (!isWordChar(before) && !isWordChar(afterC)) return true;
            idx++;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
    }

    // ──────────────────── inventory ────────────────────

    /**
     * Returns the recipe inventory the routing layer should consider.
     * Excludes:
     * <ul>
     *   <li>{@code _slart/*} — Slart's persisted past outputs are
     *       not human-curated recipes,</li>
     *   <li>{@code _*} — system-internal documents.</li>
     * </ul>
     * Engine-default tagged recipes (marvin, hactar, zaphod, …) ARE
     * included — they are matchable via their own name or via their
     * declared trigger keywords. The {@code engine-default} tag was
     * historically used to hide them from the LLM-driven inventory
     * dump; the new deterministic pre-check needs them present.
     */
    private List<ResolvedRecipe> listRecipesForRouting(ThinkProcessDocument caller) {
        try {
            List<ResolvedRecipe> all = recipeLoader.listAll(
                    caller.getTenantId(), caller.getProjectId());
            List<ResolvedRecipe> visible = new ArrayList<>(all.size());
            for (ResolvedRecipe r : all) {
                if (r.name().startsWith("_slart/")) continue;
                if (r.name().startsWith("_")) continue;
                // Internal config-profile recipes (e.g. how-do-i for the
                // DiscoveryService via LightLlmService) are never offered
                // to the DELEGATE selector — they are only loaded by name
                // through the service that owns them.
                if (r.internal()) continue;
                visible.add(r);
            }
            return visible;
        } catch (RuntimeException e) {
            log.warn("RecipeSelector: failed listing recipes for tenant='{}' project='{}': {}",
                    caller.getTenantId(), caller.getProjectId(), e.toString());
            return List.of();
        }
    }

    // ──────────────────── LLM disambiguation ────────────────────

    /**
     * Runs the LLM only over the trigger-matched candidates. Same
     * structured-output contract as before, just with a tighter
     * candidate list so the LLM can't wander into unrelated recipes.
     */
    private Result runLlmDisambiguation(
            ThinkProcessDocument caller,
            List<ResolvedRecipe> candidates,
            String taskDescription) {
        AiChat chat = buildChat(caller);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildUserPrompt(candidates, taskDescription)));

        String text;
        try {
            ChatResponse response = chat.chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
            text = response.aiMessage() == null
                    ? "" : response.aiMessage().text();
        } catch (RuntimeException e) {
            log.warn("RecipeSelector: LLM call failed: {}", e.toString());
            return Result.noneAfterTrigger("LLM call failed: " + e.getMessage());
        }
        return parseResult(text, candidates);
    }

    private String buildUserPrompt(List<ResolvedRecipe> candidates, String task) {
        StringBuilder sb = new StringBuilder();
        sb.append("== Trigger-matched recipe candidates ==\n");
        for (ResolvedRecipe r : candidates) {
            sb.append("- **").append(r.name()).append("** [engine: ")
                    .append(r.engine()).append("]");
            String desc = r.description();
            if (desc != null && !desc.isBlank()) {
                sb.append(" — ").append(desc.trim().replaceAll("\\s+", " "));
            }
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("== Task description ==\n");
        sb.append(task.trim()).append("\n\n");

        sb.append("Pick the matching candidate (or NONE) and emit a "
                + "single JSON object now.");
        return sb.toString();
    }

    // ──────────────────── response parsing ────────────────────

    private Result parseResult(String text, List<ResolvedRecipe> candidates) {
        // All NONE / failure paths here come from the LLM-
        // disambiguation stage, which only runs when the trigger
        // pre-check matched ≥1 candidate. They are therefore
        // {@link Result#noneAfterTrigger} cases — caller should
        // consult {@code routing.fallback.recipe}, not the default
        // recipe.
        if (text == null || text.isBlank()) {
            return Result.noneAfterTrigger("LLM returned empty reply");
        }
        String json = extractJsonObject(text);
        if (json == null) {
            return Result.noneAfterTrigger("LLM reply has no JSON object");
        }
        ParsedResult parsed;
        try {
            parsed = objectMapper.readValue(json, ParsedResult.class);
        } catch (RuntimeException e) {
            return Result.noneAfterTrigger("JSON parse error: " + e.getMessage());
        }
        if (parsed.decision() == null) {
            return Result.noneAfterTrigger("LLM reply missing 'decision' field");
        }
        if ("NONE".equalsIgnoreCase(parsed.decision())) {
            return Result.noneAfterTrigger(orFallback(parsed.rationale(),
                    "LLM returned NONE without rationale"));
        }
        if (!"MATCH".equalsIgnoreCase(parsed.decision())) {
            return Result.noneAfterTrigger(
                    "LLM returned unrecognised decision: " + parsed.decision());
        }
        if (parsed.recipe() == null || parsed.recipe().isBlank()) {
            return Result.noneAfterTrigger("LLM returned MATCH without a recipe name");
        }
        String picked = parsed.recipe().trim();
        for (ResolvedRecipe r : candidates) {
            if (r.name().equals(picked)) {
                return Result.match(picked, r.engine(),
                        orFallback(parsed.rationale(), ""));
            }
        }
        return Result.noneAfterTrigger(
                "LLM returned unknown recipe '" + picked + "' — not in candidate list");
    }

    private static @Nullable String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String orFallback(@Nullable String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    // ──────────────────── chat construction ────────────────────

    /**
     * Package-protected to allow tests to substitute a scripted chat.
     * Production callers always go through the bean-wired path which
     * resolves model + credentials via the project cascade.
     */
    AiChat buildChat(ThinkProcessDocument caller) {
        ChatBehavior behavior = ChatBehaviorBuilder.fromProcess(
                caller, settingService, aiModelResolver);
        AiChatOptions options = AiChatOptions.builder().build();
        return aiModelService.createChat(behavior, options);
    }

    // ──────────────────── result types ────────────────────

    /** What the selector decided. {@code engineName} is a courtesy
     *  echo so callers can log / audit without a second lookup.
     *  {@code triggerObserved} tells the caller whether the user goal
     *  contained a trigger (recipe-name / engine-name / declared
     *  keyword) — the {@code process_create} fallback chain treats
     *  {@code NONE + triggerObserved=true} (trigger seen, no match)
     *  differently from {@code NONE + triggerObserved=false} (no
     *  trigger at all → use the {@code default} recipe → ford). */
    public record Result(
            Decision decision,
            @Nullable String recipeName,
            @Nullable String engineName,
            boolean triggerObserved,
            String rationale) {

        public enum Decision { MATCH, NONE }

        public static Result match(String recipe, String engine, String rationale) {
            return new Result(Decision.MATCH, recipe, engine, true, rationale);
        }

        /** No trigger detected in the goal — caller should fall
         *  through to the standard default recipe ({@code default}
         *  → ford). The configurable fallback recipe is reserved
         *  for the trigger-observed-but-no-match case. */
        public static Result noneWithoutTrigger(String rationale) {
            return new Result(Decision.NONE, null, null, false, rationale);
        }

        /** Trigger detected but no candidate matched (multi-candidate
         *  LLM disambiguation returned NONE, or the LLM call failed).
         *  Caller should consult {@code routing.fallback.recipe}. */
        public static Result noneAfterTrigger(String rationale) {
            return new Result(Decision.NONE, null, null, true, rationale);
        }
    }

    /** Wire-format mirror of the structured-output JSON. */
    private record ParsedResult(
            @JsonProperty("decision") @Nullable String decision,
            @JsonProperty("recipe") @Nullable String recipe,
            @JsonProperty("rationale") @Nullable String rationale,
            // Tolerate extra keys gracefully — we don't want to
            // bounce the whole result if the model adds debug fields.
            @JsonProperty("notes") @Nullable Object notes) {}
}
