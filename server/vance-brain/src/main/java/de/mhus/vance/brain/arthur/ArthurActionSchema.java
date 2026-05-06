package de.mhus.vance.brain.arthur;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constants describing Arthur's structured-action vocabulary. Kept
 * in a dedicated class so the engine itself stays focused on
 * lifecycle + dispatch, and so future Arthur-style engines can
 * reuse / fork the schema without copy-pasting it inline.
 *
 * <h2>Action types (NORMAL/EXECUTING)</h2>
 *
 * <ul>
 *   <li>{@link #TYPE_ANSWER} — direct reply ready, send it.</li>
 *   <li>{@link #TYPE_ASK_USER} — need clarification from the user
 *       before doing more work.</li>
 *   <li>{@link #TYPE_DELEGATE} — spawn a worker via a recipe and
 *       (silently or with a brief pre-text) hand off the task.</li>
 *   <li>{@link #TYPE_RELAY} — pass a worker's last reply through
 *       to the user as Arthur's voice.</li>
 *   <li>{@link #TYPE_WAIT} — async work is in flight; nothing to
 *       say right now.</li>
 *   <li>{@link #TYPE_REJECT} — request out of scope.</li>
 *   <li>{@link #TYPE_START_PLAN} — switch to EXPLORING for
 *       complex tasks that need explore-then-plan-then-execute.</li>
 *   <li>{@link #TYPE_TODO_UPDATE} — update TodoList item statuses
 *       during EXECUTING.</li>
 * </ul>
 *
 * <h2>Plan-Mode action types</h2>
 *
 * <ul>
 *   <li>{@link #TYPE_PROPOSE_PLAN} — emitted in EXPLORING when
 *       exploration is done; submits plan + TodoList for user
 *       approval. Also used in PLANNING to re-emit an edited plan.</li>
 *   <li>{@link #TYPE_START_EXECUTION} — emitted in PLANNING when
 *       the user has approved the plan; switches process to
 *       EXECUTING.</li>
 * </ul>
 *
 * <p>Per-mode action availability is enforced by
 * {@code ArthurEngine.handleAction} — the schema itself is flat to
 * keep the LLM's tool surface stable. The mode-specific system
 * prompts ({@code arthur-prompt.md}, {@code arthur-prompt-exploring.md},
 * {@code arthur-prompt-planning.md}) tell the model which actions
 * to use in each mode.
 *
 * <p>Every action carries a non-blank {@code reason} (validated by
 * {@code StructuredActionEngine}) — both for audit and to force the
 * model to think about why it picked this branch.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §4.
 */
public final class ArthurActionSchema {

    private ArthurActionSchema() {}

    public static final String TOOL_NAME = "arthur_action";

    public static final String TOOL_DESCRIPTION =
            "Final structured action for this turn. Call this exactly once "
                    + "per turn (after any read-tool calls like recipe_list / "
                    + "manual_read you may need). The 'type' picks the branch, "
                    + "'reason' explains the choice, and per-type fields carry "
                    + "the content. Action availability depends on the current "
                    + "process mode — see the system prompt for what's "
                    + "available right now.";

    // ── NORMAL/EXECUTING action types ────────────────────────────
    public static final String TYPE_ANSWER     = "ANSWER";
    public static final String TYPE_ASK_USER   = "ASK_USER";
    public static final String TYPE_DELEGATE   = "DELEGATE";
    public static final String TYPE_RELAY      = "RELAY";
    public static final String TYPE_WAIT       = "WAIT";
    public static final String TYPE_REJECT     = "REJECT";

    // ── Plan-Mode action types ───────────────────────────────────
    public static final String TYPE_START_PLAN      = "START_PLAN";
    public static final String TYPE_PROPOSE_PLAN    = "PROPOSE_PLAN";
    public static final String TYPE_START_EXECUTION = "START_EXECUTION";
    public static final String TYPE_TODO_UPDATE     = "TODO_UPDATE";

    public static final Set<String> SUPPORTED_TYPES = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_RELAY,
            TYPE_WAIT, TYPE_REJECT,
            TYPE_START_PLAN, TYPE_PROPOSE_PLAN, TYPE_START_EXECUTION,
            TYPE_TODO_UPDATE);

    /**
     * Action types allowed in {@code NORMAL} mode — full vocabulary
     * minus the Plan-Mode-internal transitions ({@code PROPOSE_PLAN},
     * {@code START_EXECUTION}). {@code TODO_UPDATE} is technically
     * an EXECUTING-only concept but allowed in NORMAL too as a no-op
     * tolerance.
     */
    public static final Set<String> TYPES_FOR_NORMAL = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_RELAY,
            TYPE_WAIT, TYPE_REJECT, TYPE_START_PLAN);

    /**
     * Action types allowed in {@code EXPLORING} mode — read-only
     * exploration. Engine actions and write-style transitions are
     * blocked.
     */
    public static final Set<String> TYPES_FOR_EXPLORING = Set.of(
            TYPE_ANSWER, TYPE_PROPOSE_PLAN, TYPE_START_PLAN);

    /**
     * Action types allowed in {@code PLANNING} mode — interpreting
     * the user's reply to the proposed plan.
     */
    public static final Set<String> TYPES_FOR_PLANNING = Set.of(
            TYPE_ANSWER, TYPE_PROPOSE_PLAN, TYPE_START_EXECUTION,
            TYPE_START_PLAN);

    /**
     * Action types allowed in {@code EXECUTING} mode — full work
     * vocabulary minus Plan-Mode entry actions.
     */
    public static final Set<String> TYPES_FOR_EXECUTING = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_RELAY,
            TYPE_WAIT, TYPE_REJECT, TYPE_START_PLAN, TYPE_TODO_UPDATE);

    public static Set<String> typesForMode(
            de.mhus.vance.api.thinkprocess.ProcessMode mode) {
        return switch (mode) {
            case NORMAL -> TYPES_FOR_NORMAL;
            case EXPLORING -> TYPES_FOR_EXPLORING;
            case PLANNING -> TYPES_FOR_PLANNING;
            case EXECUTING -> TYPES_FOR_EXECUTING;
        };
    }

    // ── Param keys ───────────────────────────────────────────────
    public static final String PARAM_MESSAGE = "message";
    public static final String PARAM_PRESET  = "preset";
    public static final String PARAM_PROMPT  = "prompt";
    public static final String PARAM_SOURCE  = "source";
    public static final String PARAM_PREFIX  = "prefix";

    // Plan-Mode params
    public static final String PARAM_GOAL    = "goal";
    public static final String PARAM_PLAN    = "plan";
    public static final String PARAM_SUMMARY = "summary";
    public static final String PARAM_TODOS   = "todos";
    public static final String PARAM_NOTES   = "notes";
    public static final String PARAM_UPDATES = "updates";

    /**
     * JSON schema (flat) covering all action types. Per-type
     * required-field validation lives in
     * {@code ArthurEngine.handleAction} where the type is known.
     * Per-mode allowed-type filtering also happens there — keeps
     * the LLM's tool surface stable so prompt-cache stays warm.
     */
    public static Map<String, Object> schema() {
        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("enum", List.of(
                TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE,
                TYPE_RELAY, TYPE_WAIT, TYPE_REJECT,
                TYPE_START_PLAN, TYPE_PROPOSE_PLAN,
                TYPE_START_EXECUTION, TYPE_TODO_UPDATE));
        typeProp.put("description",
                "Which branch this turn takes. ANSWER = direct reply. "
                        + "ASK_USER = clarification question. DELEGATE = spawn "
                        + "a worker. RELAY = pass through a worker's last "
                        + "reply as your own answer. WAIT = async work running. "
                        + "REJECT = out of scope. START_PLAN = enter "
                        + "EXPLORING mode for plan-then-confirm-then-execute. "
                        + "PROPOSE_PLAN = submit plan + TodoList for user "
                        + "approval (EXPLORING/PLANNING). "
                        + "START_EXECUTION = begin work after user accepted "
                        + "the plan (PLANNING). TODO_UPDATE = update TodoList "
                        + "item statuses (EXECUTING). The system prompt tells "
                        + "you which subset is currently allowed.");

        Map<String, Object> reasonProp = new LinkedHashMap<>();
        reasonProp.put("type", "string");
        reasonProp.put("description",
                "One short sentence explaining why this action was chosen. "
                        + "Required for audit and to force deliberate decisions. "
                        + "Never empty.");

        Map<String, Object> messageProp = new LinkedHashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description",
                "User-facing text. Required for ANSWER, ASK_USER, REJECT. "
                        + "Optional for DELEGATE, WAIT. Markdown allowed.");

        Map<String, Object> presetProp = new LinkedHashMap<>();
        presetProp.put("type", "string");
        presetProp.put("description",
                "Recipe name to spawn the worker from. OPTIONAL for DELEGATE.");

        Map<String, Object> promptProp = new LinkedHashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description",
                "Concrete instruction for the worker. Required for DELEGATE.");

        Map<String, Object> sourceProp = new LinkedHashMap<>();
        sourceProp.put("type", "string");
        sourceProp.put("description",
                "Worker process name whose last reply should be relayed. "
                        + "Required for RELAY.");

        Map<String, Object> prefixProp = new LinkedHashMap<>();
        prefixProp.put("type", "string");
        prefixProp.put("description",
                "Optional short prefix prepended to relayed worker text. "
                        + "Only meaningful for RELAY.");

        // Plan-Mode params
        Map<String, Object> goalProp = new LinkedHashMap<>();
        goalProp.put("type", "string");
        goalProp.put("description",
                "Optional one-liner restating the task as you understand "
                        + "it. Used for START_PLAN — the goal you'll explore "
                        + "and plan against.");

        Map<String, Object> planProp = new LinkedHashMap<>();
        planProp.put("type", "string");
        planProp.put("description",
                "Markdown plan text. Required for PROPOSE_PLAN. The user "
                        + "reads this and accepts / edits / rejects.");

        Map<String, Object> summaryProp = new LinkedHashMap<>();
        summaryProp.put("type", "string");
        summaryProp.put("description",
                "One-line summary of the plan, used for spinner / log / "
                        + "inbox-announcement. Required for PROPOSE_PLAN.");

        Map<String, Object> todoItemSchema = new LinkedHashMap<>();
        todoItemSchema.put("type", "object");
        Map<String, Object> todoItemProps = new LinkedHashMap<>();
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "Stable id within the plan, e.g. \"1\".");
        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description",
                "Imperative form, e.g. \"Token-Storage migrieren\".");
        Map<String, Object> activeFormProp = new LinkedHashMap<>();
        activeFormProp.put("type", "string");
        activeFormProp.put("description",
                "Optional present-continuous, e.g. \"Migriere Token-Storage\".");
        todoItemProps.put("id", idProp);
        todoItemProps.put("content", contentProp);
        todoItemProps.put("activeForm", activeFormProp);
        todoItemSchema.put("properties", todoItemProps);
        todoItemSchema.put("required", List.of("id", "content"));

        Map<String, Object> todosProp = new LinkedHashMap<>();
        todosProp.put("type", "array");
        todosProp.put("items", todoItemSchema);
        todosProp.put("description",
                "TodoList: 3–8 plan steps. Required for PROPOSE_PLAN. "
                        + "Each item is a logical phase step with own value "
                        + "(not atomic tool-calls, not over-generalisations).");

        Map<String, Object> notesProp = new LinkedHashMap<>();
        notesProp.put("type", "string");
        notesProp.put("description",
                "Optional extra context from the user's approval. Used "
                        + "for START_EXECUTION.");

        Map<String, Object> updateItemSchema = new LinkedHashMap<>();
        updateItemSchema.put("type", "object");
        Map<String, Object> updateItemProps = new LinkedHashMap<>();
        Map<String, Object> updateIdProp = new LinkedHashMap<>();
        updateIdProp.put("type", "string");
        updateIdProp.put("description", "TodoItem id to update.");
        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "string");
        statusProp.put("enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED"));
        statusProp.put("description", "New status for the TodoItem.");
        updateItemProps.put("id", updateIdProp);
        updateItemProps.put("status", statusProp);
        updateItemSchema.put("properties", updateItemProps);
        updateItemSchema.put("required", List.of("id", "status"));

        Map<String, Object> updatesProp = new LinkedHashMap<>();
        updatesProp.put("type", "array");
        updatesProp.put("items", updateItemSchema);
        updatesProp.put("description",
                "TodoItem status updates. Required for TODO_UPDATE. "
                        + "Items not listed are left untouched.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typeProp);
        properties.put("reason", reasonProp);
        properties.put(PARAM_MESSAGE, messageProp);
        properties.put(PARAM_PRESET, presetProp);
        properties.put(PARAM_PROMPT, promptProp);
        properties.put(PARAM_SOURCE, sourceProp);
        properties.put(PARAM_PREFIX, prefixProp);
        properties.put(PARAM_GOAL, goalProp);
        properties.put(PARAM_PLAN, planProp);
        properties.put(PARAM_SUMMARY, summaryProp);
        properties.put(PARAM_TODOS, todosProp);
        properties.put(PARAM_NOTES, notesProp);
        properties.put(PARAM_UPDATES, updatesProp);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of("type", "reason"));
        return root;
    }
}
