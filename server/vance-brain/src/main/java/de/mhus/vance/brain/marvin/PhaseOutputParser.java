package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.marvin.ConcludeOutput;
import de.mhus.vance.api.marvin.NewTaskSpec;
import de.mhus.vance.api.marvin.PostActionSpec;
import de.mhus.vance.api.marvin.PostChildrenAction;
import de.mhus.vance.api.marvin.PostChildrenOutput;
import de.mhus.vance.api.marvin.RecipeCall;
import de.mhus.vance.api.marvin.ReflectAction;
import de.mhus.vance.api.marvin.ReflectOutput;
import de.mhus.vance.api.marvin.ScopeAction;
import de.mhus.vance.api.marvin.ScopeOutput;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.marvin.UserInputSpec;
import de.mhus.vance.api.marvin.ValidateOutput;
import de.mhus.vance.api.marvin.ValidateVerdict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses the five phase outputs of a Marvin WORKER LLM call. One
 * method per phase; each returns a {@link Result} that's either
 * {@code ok} (carrying the typed output) or {@code failure}
 * (carrying a human-readable error suitable for a correction
 * re-prompt — same UX as v1's {@code MarvinWorkerOutputParser}).
 *
 * <p>See {@code specification/marvin-engine.md} §4 for the schemas.
 */
@Component
public class PhaseOutputParser {

    private final ObjectMapper objectMapper;

    public PhaseOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─────────────────── Public API ───────────────────

    public Result<ScopeOutput> parseScope(@Nullable String raw) {
        Map<String, Object> root = tryParseJsonObject(raw);
        if (root == null) {
            return Result.failure(noJsonError("SCOPE"));
        }
        Object actionRaw = root.get("action");
        if (!(actionRaw instanceof String actionStr) || actionStr.isBlank()) {
            return Result.failure(
                    "Required field 'action' is missing — must be one of "
                            + "CALL_RECIPE, PROCEED_TO_CONCLUDE, NEEDS_SUBTASKS, "
                            + "NEEDS_USER_INPUT, BLOCKED_BY_PROBLEM.");
        }
        ScopeAction action;
        try {
            action = ScopeAction.valueOf(actionStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.failure(
                    "Unknown 'action' value '" + actionStr
                            + "' — allowed: CALL_RECIPE, PROCEED_TO_CONCLUDE, "
                            + "NEEDS_SUBTASKS, NEEDS_USER_INPUT, BLOCKED_BY_PROBLEM.");
        }
        RecipeCall recipeCall = parseRecipeCall(root.get("recipeCall"));
        if (recipeCall == null && action == ScopeAction.CALL_RECIPE) {
            // LLM put recipe/steer at the top-level — common
            // for smaller models that didn't grok the nested
            // recipeCall sub-object. Accept it.
            recipeCall = parseRecipeCallFlat(root);
        }
        List<NewTaskSpec> newTasks = parseNewTasks(root.get("newTasks"));
        UserInputSpec userInput = parseUserInput(root.get("userInput"));
        String problem = optString(root, "problem");
        String reason = optString(root, "reason");
        String violation = validateActionFields(
                action.name(), recipeCall, newTasks, userInput, problem);
        if (violation != null) return Result.failure(violation);
        return Result.ok(new ScopeOutput(
                action, recipeCall, newTasks, userInput, problem, reason));
    }

    public Result<ReflectOutput> parseReflect(@Nullable String raw) {
        Map<String, Object> root = tryParseJsonObject(raw);
        if (root == null) {
            return Result.failure(noJsonError("REFLECT"));
        }
        Object actionRaw = root.get("action");
        if (!(actionRaw instanceof String actionStr) || actionStr.isBlank()) {
            return Result.failure(
                    "Required field 'action' is missing — must be one of "
                            + "CALL_RECIPE, PROCEED_TO_CONCLUDE, NEEDS_SUBTASKS, "
                            + "NEEDS_USER_INPUT, BLOCKED_BY_PROBLEM.");
        }
        ReflectAction action;
        try {
            action = ReflectAction.valueOf(actionStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.failure(
                    "Unknown 'action' value '" + actionStr
                            + "' — allowed: CALL_RECIPE, PROCEED_TO_CONCLUDE, "
                            + "NEEDS_SUBTASKS, NEEDS_USER_INPUT, BLOCKED_BY_PROBLEM.");
        }
        RecipeCall recipeCall = parseRecipeCall(root.get("recipeCall"));
        if (recipeCall == null && action == ReflectAction.CALL_RECIPE) {
            recipeCall = parseRecipeCallFlat(root);
        }
        List<NewTaskSpec> newTasks = parseNewTasks(root.get("newTasks"));
        UserInputSpec userInput = parseUserInput(root.get("userInput"));
        String problem = optString(root, "problem");
        String reason = optString(root, "reason");
        String violation = validateActionFields(
                action.name(), recipeCall, newTasks, userInput, problem);
        if (violation != null) return Result.failure(violation);
        return Result.ok(new ReflectOutput(
                action, recipeCall, newTasks, userInput, problem, reason));
    }

    public Result<PostChildrenOutput> parsePostChildren(@Nullable String raw) {
        Map<String, Object> root = tryParseJsonObject(raw);
        if (root == null) {
            return Result.failure(noJsonError("POST_CHILDREN"));
        }
        Object actionRaw = root.get("action");
        if (!(actionRaw instanceof String actionStr) || actionStr.isBlank()) {
            return Result.failure(
                    "Required field 'action' is missing — must be one of "
                            + "PROCEED_TO_CONCLUDE, NEEDS_SUBTASKS, BLOCKED_BY_PROBLEM.");
        }
        PostChildrenAction action;
        try {
            action = PostChildrenAction.valueOf(actionStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.failure(
                    "Unknown 'action' value '" + actionStr + "'.");
        }
        List<NewTaskSpec> newTasks = parseNewTasks(root.get("newTasks"));
        String problem = optString(root, "problem");
        String reason = optString(root, "reason");
        if (action == PostChildrenAction.NEEDS_SUBTASKS
                && (newTasks == null || newTasks.isEmpty())) {
            return Result.failure(
                    "'newTasks' must be a non-empty array when "
                            + "action=NEEDS_SUBTASKS.");
        }
        if (action == PostChildrenAction.BLOCKED_BY_PROBLEM && problem == null) {
            return Result.failure(
                    "'problem' is required when action=BLOCKED_BY_PROBLEM.");
        }
        return Result.ok(new PostChildrenOutput(
                action, newTasks, problem, reason));
    }

    public Result<ConcludeOutput> parseConclude(@Nullable String raw) {
        Map<String, Object> root = tryParseJsonObject(raw);
        if (root == null) {
            return Result.failure(noJsonError("CONCLUDE"));
        }
        String result = optString(root, "result");
        if (result == null) {
            return Result.failure(
                    "'result' is required in CONCLUDE — the markdown final answer.");
        }
        List<PostActionSpec> postActions = parsePostActions(root.get("postActions"));
        String reason = optString(root, "reason");
        return Result.ok(new ConcludeOutput(result, postActions, reason));
    }

    public Result<ValidateOutput> parseValidate(@Nullable String raw) {
        Map<String, Object> root = tryParseJsonObject(raw);
        if (root == null) {
            return Result.failure(noJsonError("VALIDATE"));
        }
        Object verdictRaw = root.get("verdict");
        if (!(verdictRaw instanceof String verdictStr) || verdictStr.isBlank()) {
            return Result.failure(
                    "Required field 'verdict' is missing — must be one of "
                            + "PASS, RETRY_CONCLUDE, NEED_MORE_DATA, HARD_FAIL.");
        }
        ValidateVerdict verdict;
        try {
            verdict = ValidateVerdict.valueOf(verdictStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.failure(
                    "Unknown 'verdict' value '" + verdictStr
                            + "' — allowed: PASS, RETRY_CONCLUDE, "
                            + "NEED_MORE_DATA, HARD_FAIL.");
        }
        List<String> issues = parseStringList(root.get("issues"));
        String hint = optString(root, "hint");
        String reason = optString(root, "reason");
        return Result.ok(new ValidateOutput(verdict, issues, hint, reason));
    }

    // ─────────────────── Helpers ───────────────────

    private static String noJsonError(String phase) {
        return "Worker reply for phase " + phase
                + " does not contain a JSON object. End your reply with a"
                + " single JSON object matching the " + phase + " schema.";
    }

    private @Nullable Map<String, Object> tryParseJsonObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = de.mhus.vance.shared.util.JsonReplyExtractor.extractLastObject(raw);
        if (json == null) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable RecipeCall parseRecipeCall(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object rec = m.get("recipe");
        if (!(rec instanceof String r) || r.isBlank()) return null;
        // Accept several common LLM aliases for steerContent — small
        // models sometimes use `steer`, `prompt`, `query`, `input`,
        // `content` or `args.query` instead of the canonical
        // `steerContent`.
        Object steer = m.get("steerContent");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = m.get("steer");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = m.get("prompt");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = m.get("query");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = m.get("input");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = m.get("content");
        if (!(steer instanceof String s) || s.isBlank()) return null;
        return new RecipeCall(r.trim(), s);
    }

    /**
     * Fallback for LLMs that put {@code recipe} + steer directly on
     * the top-level JSON object instead of inside a nested
     * {@code recipeCall} sub-object. Builds a synthetic
     * {@link RecipeCall} from {@code root}.
     */
    private static @Nullable RecipeCall parseRecipeCallFlat(Map<String, Object> root) {
        Object rec = root.get("recipe");
        if (!(rec instanceof String r) || r.isBlank()) return null;
        // Look for steer in several spots — top-level or
        // common nested locations like `parameters.query`.
        Object steer = root.get("steerContent");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = root.get("steer");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = root.get("prompt");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = root.get("query");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = root.get("input");
        if (!(steer instanceof String) || ((String) steer).isBlank()) steer = root.get("content");
        if (!(steer instanceof String) || ((String) steer).isBlank()) {
            Object params = root.get("parameters");
            if (params instanceof Map<?, ?> pm) {
                Object q = pm.get("query");
                if (q instanceof String qs && !qs.isBlank()) steer = qs;
                else {
                    Object p = pm.get("prompt");
                    if (p instanceof String ps && !ps.isBlank()) steer = ps;
                    else {
                        Object i = pm.get("input");
                        if (i instanceof String is && !is.isBlank()) steer = is;
                    }
                }
            }
            if (!(steer instanceof String) || ((String) steer).isBlank()) {
                Object args = root.get("args");
                if (args instanceof Map<?, ?> am) {
                    Object q = am.get("query");
                    if (q instanceof String qs && !qs.isBlank()) steer = qs;
                    else {
                        Object p = am.get("prompt");
                        if (p instanceof String ps && !ps.isBlank()) steer = ps;
                    }
                }
            }
        }
        if (!(steer instanceof String s) || s.isBlank()) return null;
        return new RecipeCall(r.trim(), s);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<NewTaskSpec> parseNewTasks(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<NewTaskSpec> out = new ArrayList<>();
        for (Object e : list) {
            if (!(e instanceof Map<?, ?> spec)) continue;
            Object goalRaw = spec.get("goal");
            Object kindRaw = spec.get("taskKind");
            if (!(goalRaw instanceof String goal) || goal.isBlank()) continue;
            TaskKind kind = TaskKind.WORKER;
            if (kindRaw instanceof String kindStr && !kindStr.isBlank()) {
                try {
                    kind = TaskKind.valueOf(kindStr.trim().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
            Map<String, Object> taskSpec = new LinkedHashMap<>();
            Object specRaw = spec.get("taskSpec");
            if (specRaw instanceof Map<?, ?> ms) {
                for (Map.Entry<?, ?> en : ms.entrySet()) {
                    taskSpec.put(String.valueOf(en.getKey()), en.getValue());
                }
            }
            out.add(new NewTaskSpec(goal, kind, taskSpec));
        }
        return out.isEmpty() ? null : out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable UserInputSpec parseUserInput(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object type = m.get("type");
        Object title = m.get("title");
        Object body = m.get("body");
        Object crit = m.get("criticality");
        Object payloadRaw = m.get("payload");
        String typeStr = (type instanceof String ts && !ts.isBlank()) ? ts : "FEEDBACK";
        String titleStr = (title instanceof String s && !s.isBlank()) ? s : "";
        @Nullable String bodyStr = (body instanceof String s && !s.isBlank()) ? s : null;
        @Nullable String critStr = (crit instanceof String s && !s.isBlank()) ? s : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        if (payloadRaw instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> en : pm.entrySet()) {
                payload.put(String.valueOf(en.getKey()), en.getValue());
            }
        }
        return new UserInputSpec(typeStr, titleStr, bodyStr, critStr, payload);
    }

    private static @Nullable List<String> parseStringList(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof String s && !s.isBlank()) out.add(s);
        }
        return out.isEmpty() ? null : out;
    }

    private static @Nullable List<PostActionSpec> parsePostActions(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<PostActionSpec> out = new ArrayList<>();
        for (Object e : list) {
            if (!(e instanceof Map<?, ?> m)) continue;
            Object tool = m.get("tool");
            if (!(tool instanceof String t)) tool = m.get("toolName");
            if (!(tool instanceof String t2) || t2.isBlank()) continue;
            Object argsRaw = m.get("args");
            if (argsRaw == null) argsRaw = m.get("params");
            Map<String, Object> args = new LinkedHashMap<>();
            if (argsRaw instanceof Map<?, ?> am) {
                for (Map.Entry<?, ?> en : am.entrySet()) {
                    args.put(String.valueOf(en.getKey()), en.getValue());
                }
            }
            out.add(new PostActionSpec(String.valueOf(tool), args));
        }
        return out.isEmpty() ? null : out;
    }

    private static @Nullable String optString(Map<String, Object> m, String key) {
        Object raw = m.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Validates that the fields required for the given action are
     * present and that mutually-exclusive fields aren't all set.
     * Returns a human-readable error message or {@code null} if OK.
     * Shared by SCOPE and REFLECT (their actions overlap).
     */
    private static @Nullable String validateActionFields(
            String action,
            @Nullable RecipeCall recipeCall,
            @Nullable List<NewTaskSpec> newTasks,
            @Nullable UserInputSpec userInput,
            @Nullable String problem) {
        switch (action) {
            case "CALL_RECIPE" -> {
                if (recipeCall == null) {
                    return "'recipeCall.recipe' and 'recipeCall.steerContent' "
                            + "are required when action=CALL_RECIPE.";
                }
            }
            case "NEEDS_SUBTASKS" -> {
                if (newTasks == null || newTasks.isEmpty()) {
                    return "'newTasks' must be a non-empty array when "
                            + "action=NEEDS_SUBTASKS.";
                }
            }
            case "NEEDS_USER_INPUT" -> {
                if (userInput == null) {
                    return "'userInput' object is required when "
                            + "action=NEEDS_USER_INPUT.";
                }
            }
            case "BLOCKED_BY_PROBLEM" -> {
                if (problem == null) {
                    return "'problem' is required when "
                            + "action=BLOCKED_BY_PROBLEM.";
                }
            }
            case "PROCEED_TO_CONCLUDE" -> {
                /* no required side-fields */
            }
        }
        return null;
    }

    /**
     * Result of a parse attempt — either the typed phase output or
     * an error message suitable for a correction-reprompt.
     */
    public static final class Result<T> {
        private final @Nullable T output;
        private final @Nullable String error;

        private Result(@Nullable T output, @Nullable String error) {
            this.output = output;
            this.error = error;
        }

        public static <T> Result<T> ok(T output) {
            return new Result<>(output, null);
        }

        public static <T> Result<T> failure(String error) {
            return new Result<>(null, error);
        }

        public boolean ok() { return output != null; }
        public T output() { return output; }
        public String error() { return error; }
    }
}
