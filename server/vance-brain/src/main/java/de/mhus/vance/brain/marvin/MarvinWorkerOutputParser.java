package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.marvin.MarvinWorkerOutput;
import de.mhus.vance.api.marvin.MarvinWorkerOutput.NewTaskSpec;
import de.mhus.vance.api.marvin.MarvinWorkerOutput.UserInputSpec;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.marvin.WorkerOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Extracts and validates a {@link MarvinWorkerOutput} from a raw
 * worker reply. Workers are instructed to end with a JSON block of
 * the documented shape — this parser is the single place that
 * implements the contract.
 *
 * <p>Returns a {@link Result} with either the parsed output or a
 * concrete error message that {@link MarvinEngine} feeds back as a
 * correction prompt before retrying.
 */
@Component
public class MarvinWorkerOutputParser {

    private final ObjectMapper objectMapper;

    public MarvinWorkerOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parse the raw worker reply text. */
    public Result parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.failure("Worker reply is empty.");
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return Result.failure(
                    "Worker reply does not contain a JSON object. "
                            + "End your reply with a single fenced or unfenced "
                            + "JSON object matching the Marvin worker schema.");
        }
        Map<String, Object> root;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            root = parsed;
        } catch (RuntimeException e) {
            return Result.failure("JSON is not valid: " + e.getMessage());
        }
        return validate(root);
    }

    /**
     * Strips wrapping markdown / prose around the JSON. Picks the
     * LAST matching object in the reply so the trailing schema
     * block wins over any earlier example/quote.
     */
    private static @Nullable String extractJsonObject(String raw) {
        int end = raw.lastIndexOf('}');
        if (end < 0) return null;
        // Walk back from end looking for the matching '{'.
        int depth = 0;
        int start = -1;
        for (int i = end; i >= 0; i--) {
            char c = raw.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                depth--;
                if (depth == 0) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) return null;
        return raw.substring(start, end + 1);
    }

    private Result validate(Map<String, Object> root) {
        Object outcomeRaw = root.get("outcome");
        if (!(outcomeRaw instanceof String outcomeStr) || outcomeStr.isBlank()) {
            return Result.failure(
                    "Required field 'outcome' is missing — must be one of "
                            + "DONE, NEEDS_SUBTASKS, NEEDS_USER_INPUT, "
                            + "BLOCKED_BY_PROBLEM.");
        }
        WorkerOutcome outcome;
        try {
            outcome = WorkerOutcome.valueOf(outcomeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.failure(
                    "Unknown 'outcome' value '" + outcomeStr
                            + "'. Allowed: DONE, NEEDS_SUBTASKS, "
                            + "NEEDS_USER_INPUT, BLOCKED_BY_PROBLEM.");
        }

        String result = optString(root, "result");
        String problem = optString(root, "problem");
        String reason = optString(root, "reason");

        // Per-outcome required fields.
        switch (outcome) {
            case DONE -> {
                if (result == null) {
                    return Result.failure(
                            "'result' is required when outcome=DONE — "
                                    + "the worker's final answer.");
                }
            }
            case BLOCKED_BY_PROBLEM -> {
                if (problem == null) {
                    return Result.failure(
                            "'problem' is required when "
                                    + "outcome=BLOCKED_BY_PROBLEM.");
                }
            }
            case NEEDS_SUBTASKS -> {
                Object tasksRaw = root.get("newTasks");
                if (!(tasksRaw instanceof List<?> list) || list.isEmpty()) {
                    return Result.failure(
                            "'newTasks' must be a non-empty array when "
                                    + "outcome=NEEDS_SUBTASKS.");
                }
            }
            case NEEDS_USER_INPUT -> {
                Object userInputRaw = root.get("userInput");
                if (!(userInputRaw instanceof Map<?, ?>)) {
                    return Result.failure(
                            "'userInput' object is required when "
                                    + "outcome=NEEDS_USER_INPUT.");
                }
            }
        }

        List<NewTaskSpec> newTasks = parseNewTasks(root.get("newTasks"));
        UserInputSpec userInput = parseUserInput(root.get("userInput"));

        MarvinWorkerOutput out = MarvinWorkerOutput.builder()
                .outcome(outcome)
                .result(result)
                .newTasks(newTasks)
                .userInput(userInput)
                .problem(problem)
                .reason(reason)
                .build();
        return Result.ok(out);
    }

    @SuppressWarnings("unchecked")
    private static List<NewTaskSpec> parseNewTasks(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
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
            out.add(NewTaskSpec.builder()
                    .goal(goal).taskKind(kind).taskSpec(taskSpec).build());
        }
        return out;
    }

    private static @Nullable UserInputSpec parseUserInput(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        UserInputSpec spec = new UserInputSpec();
        Object t = m.get("type");
        if (t instanceof String s && !s.isBlank()) spec.setType(s);
        Object title = m.get("title");
        if (title instanceof String s && !s.isBlank()) spec.setTitle(s);
        Object body = m.get("body");
        if (body instanceof String s && !s.isBlank()) spec.setBody(s);
        Object crit = m.get("criticality");
        if (crit instanceof String s && !s.isBlank()) spec.setCriticality(s);
        Object pl = m.get("payload");
        if (pl instanceof Map<?, ?> pm) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : pm.entrySet()) {
                payload.put(String.valueOf(en.getKey()), en.getValue());
            }
            spec.setPayload(payload);
        }
        return spec;
    }

    private static @Nullable String optString(Map<String, Object> m, String key) {
        Object raw = m.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    /** Either a parsed output, or an error message suitable for a
     *  correction-reprompt. */
    public static final class Result {
        private final @Nullable MarvinWorkerOutput output;
        private final @Nullable String error;

        private Result(@Nullable MarvinWorkerOutput output, @Nullable String error) {
            this.output = output;
            this.error = error;
        }

        public static Result ok(MarvinWorkerOutput output) {
            return new Result(output, null);
        }

        public static Result failure(String error) {
            return new Result(null, error);
        }

        public boolean ok() { return output != null; }
        public MarvinWorkerOutput output() { return output; }
        public String error() { return error; }
    }
}
