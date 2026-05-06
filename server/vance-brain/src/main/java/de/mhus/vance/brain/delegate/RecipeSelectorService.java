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
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Picks a project recipe for a free-text task description. Single
 * synchronous LLM call — no sub-process spawn, no tool turn — so
 * {@code process_create_delegate} stays cheap.
 *
 * <p>The prompt embeds two reference blocks:
 *
 * <ol>
 *   <li>{@link EngineCatalog} — what each engine is for, hand-curated.</li>
 *   <li>The project's recipe inventory (cascade: project → tenant →
 *       bundled defaults), with each recipe's name + engine +
 *       description.</li>
 * </ol>
 *
 * <p>Output is structured JSON: {@code {"decision":"MATCH"|"NONE",
 * "recipe":<name>|null,"rationale":<text>}}. Returning
 * {@code NONE} is a first-class outcome — caller decides whether to
 * fall back to Slartibartfast (generate a new recipe) or escalate
 * to the user. The selector itself takes no spawn action.
 *
 * <p>This service is the only LLM-using piece of
 * {@code process_create_delegate}; the tool wraps it with the
 * downstream {@code process_create} dispatch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeSelectorService {

    /** Selector identity used for tracing / model-alias namespacing. */
    private static final String SELECTOR_NAME = "recipe-selector";

    /** Cap the recipe inventory we pour into the prompt. Beyond this
     *  the prompt becomes noisy and the LLM picks worse. Real
     *  projects won't hit this limit; we'd need a pre-filter
     *  (e.g. tag-based) before then. */
    private static final int RECIPE_LIST_LIMIT = 50;

    /** Recipes carrying this tag are engine-default wrappers
     *  (arthur, ford, marvin-worker, zaphod) — they exist so
     *  {@code process_create(engine="X")} resolves to a valid
     *  recipe, but they are not user-task-oriented and would only
     *  add noise to the selector prompt. Hidden from the inventory. */
    private static final String INTERNAL_TAG = "engine-default";

    private static final String SYSTEM_PROMPT = """
            You are the recipe selector for the Vance multi-engine
            think-system. Given a free-text task description, you pick
            the best-matching project recipe (which carries an engine
            and default parameters), or report that no recipe matches.

            HARD OUTPUT CONTRACT:
            - End your reply with EXACTLY one JSON object.
            - NO markdown code fence (no ```json … ```).
            - NO prose before or after the JSON.

            Schema (every field mandatory, even when null):
                {
                  "decision":  "MATCH" | "NONE",
                  "recipe":    "<recipe-name from the catalog>" | null,
                  "rationale": "<1-2 sentences: why this recipe, or
                                why nothing matched>"
                }

            Decision rules:
            - "MATCH" when the task can plausibly be done by one of
              the listed recipes. The recipe-name MUST appear
              VERBATIM in the recipe inventory below — no inventing
              names.
            - "NONE" when no recipe is a reasonable fit, OR when the
              task is too ambiguous to pick safely. The caller will
              fall back to Slartibartfast (generate a new recipe) or
              ask the user to clarify.

            Selection guidance:
            - Match on the user's INTENT, not surface words. "Schreib
              mir was Schönes mit den Notizen" matches a
              note-processing or essay recipe better than a
              cooking-recipe (even if the German word "Rezept" appears
              elsewhere). The engine catalog tells you which engine
              shape fits which task type — use it.
            - Prefer specific recipes over generic ones when the
              specific one truly fits. A recipe named
              {@code essay-pipeline} is a better match for "write me
              an essay" than the generic {@code default}.
            - "NONE" is a respectable answer. Better to escalate than
              to force a poor fit.
            """;

    private final ObjectMapper objectMapper;
    private final EngineCatalog engineCatalog;
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
            return Result.none("empty task description");
        }
        List<ResolvedRecipe> recipes = listRecipes(caller);
        if (recipes.isEmpty()) {
            return Result.none(
                    "no recipes available in this project / tenant — "
                            + "fall back to Slartibartfast");
        }

        AiChat chat = buildChat(caller);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildUserPrompt(recipes, taskDescription)));

        String text;
        try {
            ChatResponse response = chat.chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
            text = response.aiMessage() == null
                    ? "" : response.aiMessage().text();
        } catch (RuntimeException e) {
            log.warn("RecipeSelector: LLM call failed: {}", e.toString());
            return Result.none("LLM call failed: " + e.getMessage());
        }

        return parseResult(text, recipes);
    }

    // ──────────────────── prompt helpers ────────────────────

    private List<ResolvedRecipe> listRecipes(ThinkProcessDocument caller) {
        try {
            List<ResolvedRecipe> all = recipeLoader.listAll(
                    caller.getTenantId(), caller.getProjectId());
            // The selector is for "pick something the user can act on
            // immediately". Filtered out:
            //   - `_slart/*`      — Slart's own past outputs (not
            //                       human-curated workflows)
            //   - `_*`            — system-internal documents
            //   - tag `engine-default` — engine-name aliases (arthur,
            //                       ford, marvin-worker, zaphod) used
            //                       by direct-engine spawns; they hold
            //                       no task-specific intent and would
            //                       only dilute the selector prompt.
            List<ResolvedRecipe> visible = new ArrayList<>();
            for (ResolvedRecipe r : all) {
                if (r.name().startsWith("_slart/")) continue;
                if (r.name().startsWith("_")) continue;
                if (r.tags() != null && r.tags().contains(INTERNAL_TAG)) continue;
                visible.add(r);
                if (visible.size() >= RECIPE_LIST_LIMIT) break;
            }
            return visible;
        } catch (RuntimeException e) {
            log.warn("RecipeSelector: failed listing recipes for tenant='{}' project='{}': {}",
                    caller.getTenantId(), caller.getProjectId(), e.toString());
            return List.of();
        }
    }

    private String buildUserPrompt(List<ResolvedRecipe> recipes, String task) {
        StringBuilder sb = new StringBuilder();

        String catalog = engineCatalog.renderForPrompt();
        if (!catalog.isEmpty()) {
            sb.append("== Engines (what each is for) ==\n");
            sb.append(catalog).append('\n');
        }

        sb.append("== Recipes available in this project ==\n");
        for (ResolvedRecipe r : recipes) {
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

        sb.append("Pick the matching recipe (or NONE) and emit a single "
                + "JSON object now.");
        return sb.toString();
    }

    // ──────────────────── response parsing ────────────────────

    private Result parseResult(String text, List<ResolvedRecipe> recipes) {
        if (text == null || text.isBlank()) {
            return Result.none("LLM returned empty reply");
        }
        String json = extractJsonObject(text);
        if (json == null) {
            return Result.none("LLM reply has no JSON object");
        }
        ParsedResult parsed;
        try {
            parsed = objectMapper.readValue(json, ParsedResult.class);
        } catch (RuntimeException e) {
            return Result.none("JSON parse error: " + e.getMessage());
        }
        if (parsed.decision() == null) {
            return Result.none("LLM reply missing 'decision' field");
        }
        if ("NONE".equalsIgnoreCase(parsed.decision())) {
            return Result.none(orFallback(parsed.rationale(),
                    "LLM returned NONE without rationale"));
        }
        if (!"MATCH".equalsIgnoreCase(parsed.decision())) {
            return Result.none(
                    "LLM returned unrecognised decision: " + parsed.decision());
        }
        if (parsed.recipe() == null || parsed.recipe().isBlank()) {
            return Result.none("LLM returned MATCH without a recipe name");
        }
        // Existence check — same defensive posture as the
        // ValidatingPhase recipes-exist rule. If the LLM still
        // hallucinates a name despite the strict prompt, we catch
        // it here rather than handing junk to process_create.
        String picked = parsed.recipe().trim();
        for (ResolvedRecipe r : recipes) {
            if (r.name().equals(picked)) {
                return Result.match(picked, r.engine(),
                        orFallback(parsed.rationale(), ""));
            }
        }
        return Result.none(
                "LLM returned unknown recipe '" + picked + "' — not in inventory");
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
     *  echo so callers can log / audit without a second lookup. */
    public record Result(
            Decision decision,
            @Nullable String recipeName,
            @Nullable String engineName,
            String rationale) {

        public enum Decision { MATCH, NONE }

        public static Result match(String recipe, String engine, String rationale) {
            return new Result(Decision.MATCH, recipe, engine, rationale);
        }

        public static Result none(String rationale) {
            return new Result(Decision.NONE, null, null, rationale);
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
