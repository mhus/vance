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

    /**
     * Looks up a user-mentioned term / intent in the Vance knowledge
     * surface (manuals, skills, server tools) before deciding what to
     * do. CONTINUING action — the engine calls {@code DiscoveryService},
     * injects the result as the action tool's tool-result, and the
     * action-loop iterates again so the LLM can pick a real action
     * (ANSWER / DELEGATE / …) with the discovery result in hand. Use
     * for **user-introduced terminology** the LLM doesn't recognise
     * (jargon, invented features, ambiguous metaphors). For LLM-side
     * lookups (verify a fence syntax before drafting), the read-only
     * {@code how_do_i} tool stays available.
     */
    public static final String TYPE_DISCOVER   = "DISCOVER";

    /**
     * Persists something about the user into the cross-engine per-user
     * memory store. Mirrors Eddie's {@code LEARN} — same {@code scope}
     * (persona|fact) + {@code mode} (replace|append) semantics, same
     * underlying {@code UserMemoryService}. Allowed in every mode so
     * the model can capture a user fact whenever it surfaces, without
     * waiting for a mode transition.
     */
    public static final String TYPE_LEARN      = "LEARN";

    // ── Plan-Mode action types ───────────────────────────────────
    public static final String TYPE_START_PLAN      = "START_PLAN";
    public static final String TYPE_PROPOSE_PLAN    = "PROPOSE_PLAN";
    public static final String TYPE_START_EXECUTION = "START_EXECUTION";
    public static final String TYPE_TODO_UPDATE     = "TODO_UPDATE";

    // ── LEARN scope / mode (mirror EddieActionSchema) ────────────
    public static final String LEARN_SCOPE_PERSONA = "persona";
    public static final String LEARN_SCOPE_FACT    = "fact";
    public static final Set<String> LEARN_SCOPES   = Set.of(
            LEARN_SCOPE_PERSONA, LEARN_SCOPE_FACT);
    public static final String LEARN_MODE_APPEND   = "append";
    public static final String LEARN_MODE_REPLACE  = "replace";

    public static final Set<String> SUPPORTED_TYPES = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_RELAY,
            TYPE_WAIT, TYPE_REJECT, TYPE_LEARN, TYPE_DISCOVER,
            TYPE_START_PLAN, TYPE_PROPOSE_PLAN, TYPE_START_EXECUTION,
            TYPE_TODO_UPDATE);

    /**
     * Action types allowed in {@code NORMAL} mode — full vocabulary
     * minus the Plan-Mode-internal transitions ({@code PROPOSE_PLAN},
     * {@code START_EXECUTION}). {@code TODO_UPDATE} is technically
     * an EXECUTING-only concept but allowed in NORMAL too as a no-op
     * tolerance. {@code LEARN} is allowed everywhere so user-fact
     * capture isn't gated by mode.
     */
    public static final Set<String> TYPES_FOR_NORMAL = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_RELAY,
            TYPE_WAIT, TYPE_REJECT, TYPE_LEARN, TYPE_DISCOVER, TYPE_START_PLAN);

    /**
     * Action types allowed in {@code EXPLORING} mode — read-only
     * exploration. Engine actions and write-style transitions are
     * blocked. {@code LEARN} stays available — it's a side-effect on
     * user memory, not on the project workspace.
     */
    public static final Set<String> TYPES_FOR_EXPLORING = Set.of(
            TYPE_ANSWER, TYPE_LEARN, TYPE_DISCOVER,
            TYPE_PROPOSE_PLAN, TYPE_START_PLAN);

    /**
     * Action types allowed in {@code PLANNING} mode — interpreting
     * the user's reply to the proposed plan.
     */
    public static final Set<String> TYPES_FOR_PLANNING = Set.of(
            TYPE_ANSWER, TYPE_LEARN, TYPE_PROPOSE_PLAN, TYPE_START_EXECUTION,
            TYPE_START_PLAN);

    /**
     * Action types allowed in {@code EXECUTING} mode — full work
     * vocabulary minus Plan-Mode entry actions.
     */
    public static final Set<String> TYPES_FOR_EXECUTING = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_RELAY,
            TYPE_WAIT, TYPE_REJECT, TYPE_LEARN, TYPE_DISCOVER,
            TYPE_START_PLAN, TYPE_TODO_UPDATE);

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
    /**
     * RELAY: stable handle of the {@code <process-event>} to relay,
     * copied verbatim from the {@code eventId} attribute the engine
     * rendered into the inbox snapshot. The engine validates the ref
     * against the current drain and pulls the worker's reply from
     * that specific event — so a stale {@code <process-event>}
     * sitting around from a previous turn can no longer be relayed
     * as if it were a fresh worker output. See
     * {@code planning/arthur-process-event-attribution.md}.
     */
    public static final String PARAM_EVENT_REF = "eventRef";

    // LEARN params — mirror EddieActionSchema for symmetry.
    /** LEARN scope discriminator: {@code persona} or {@code fact}. */
    public static final String PARAM_SCOPE   = "scope";
    /** LEARN persona-update mode: {@code append} or {@code replace}. */
    public static final String PARAM_MODE    = "mode";
    /** LEARN body — persona-update text or fact-journal entry. */
    public static final String PARAM_CONTENT = "content";

    /**
     * Optional structured options for {@link #TYPE_ASK_USER}. Each
     * entry is {@code { "label": "…", "description"?: "…" }}. The
     * handler renders them as a Markdown bullet list below the
     * question — UIs can render the same as a picker; voice channels
     * read each option aloud. Empty / null → free-text question.
     * Mirrors the same field in {@code EddieActionSchema} (see
     * specification/eddie-engine.md §5.6 / §5.8).
     */
    public static final String PARAM_OPTIONS = "options";

    // Plan-Mode params
    public static final String PARAM_GOAL    = "goal";
    public static final String PARAM_PLAN    = "plan";
    public static final String PARAM_SUMMARY = "summary";
    public static final String PARAM_TODOS   = "todos";
    public static final String PARAM_NOTES   = "notes";
    public static final String PARAM_UPDATES = "updates";

    /** DISCOVER intent — the user-mentioned term / question / phrase
     *  that the LLM doesn't recognise. Passed to
     *  {@code DiscoveryService.discover}. */
    public static final String PARAM_INTENT  = "intent";

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
                TYPE_RELAY, TYPE_WAIT, TYPE_REJECT, TYPE_LEARN,
                TYPE_DISCOVER, TYPE_START_PLAN, TYPE_PROPOSE_PLAN,
                TYPE_START_EXECUTION, TYPE_TODO_UPDATE));
        typeProp.put("description",
                "Which branch this turn takes. ANSWER = direct reply. "
                        + "ASK_USER = clarification question. DELEGATE = spawn "
                        + "a worker. RELAY = pass through a specific "
                        + "<process-event> from your current inbox — pick "
                        + "the eventId, the engine renders the source header "
                        + "and worker reply deterministically. WAIT = async "
                        + "work running. "
                        + "REJECT = out of scope. LEARN = persist something "
                        + "about the user (persona summary or specific fact) "
                        + "into the cross-engine per-user memory. DISCOVER = "
                        + "look up a user-mentioned term in Vance's manuals / "
                        + "skills / tools BEFORE deciding what to do — picks "
                        + "the right downstream action with the result in hand. "
                        + "START_PLAN = enter EXPLORING mode for plan-then-"
                        + "confirm-then-execute. PROPOSE_PLAN = submit plan + "
                        + "TodoList for user approval (EXPLORING/PLANNING). "
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
                        + "Optional for DELEGATE, WAIT, LEARN. Markdown allowed.");

        Map<String, Object> scopeProp = new LinkedHashMap<>();
        scopeProp.put("type", "string");
        scopeProp.put("enum", List.of(LEARN_SCOPE_PERSONA, LEARN_SCOPE_FACT));
        scopeProp.put("description",
                "LEARN scope. 'persona' = how-to-talk-to-this-user summary, "
                        + "always loaded into the prompt (use for persona "
                        + "traits, communication style, preferences about the "
                        + "assistant's behavior). 'fact' = a specific user "
                        + "fact (birthday, favorite color, dislike, hobby) "
                        + "appended to the journal. Required for LEARN.");

        Map<String, Object> modeProp = new LinkedHashMap<>();
        modeProp.put("type", "string");
        modeProp.put("enum", List.of(LEARN_MODE_APPEND, LEARN_MODE_REPLACE));
        modeProp.put("description",
                "LEARN persona update mode. 'replace' (default) overwrites "
                        + "the entire persona summary — use when you want a "
                        + "clean rewrite. 'append' adds to the end. Ignored "
                        + "for scope=fact (facts are always appended).");

        Map<String, Object> learnContentProp = new LinkedHashMap<>();
        learnContentProp.put("type", "string");
        learnContentProp.put("description",
                "LEARN body — persona-update text or factual journal entry. "
                        + "Required for LEARN.");

        Map<String, Object> presetProp = new LinkedHashMap<>();
        presetProp.put("type", "string");
        presetProp.put("description",
                "Recipe name to spawn the worker from. OPTIONAL for DELEGATE.");

        Map<String, Object> promptProp = new LinkedHashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description",
                "Concrete instruction for the worker. Required for DELEGATE.");

        Map<String, Object> eventRefProp = new LinkedHashMap<>();
        eventRefProp.put("type", "string");
        eventRefProp.put("description",
                "Stable handle of the <process-event> to relay — copy "
                        + "the `eventId` attribute verbatim from one of the "
                        + "<process-event> markers in your current inbox. "
                        + "Required for RELAY. The engine validates that "
                        + "the eventId belongs to the current drain (no "
                        + "relaying of stale events from previous turns) "
                        + "and renders a deterministic header that names "
                        + "the source worker — you do not write the "
                        + "header yourself.");

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

        // ASK_USER options — optional structured picker. Same shape
        // as EddieActionSchema; the handler renders them as a Markdown
        // bullet list and picker-aware UIs can sniff them out of the
        // action params for button rendering.
        Map<String, Object> optionItemSchema = new LinkedHashMap<>();
        optionItemSchema.put("type", "object");
        Map<String, Object> optionItemProps = new LinkedHashMap<>();
        Map<String, Object> optionLabelProp = new LinkedHashMap<>();
        optionLabelProp.put("type", "string");
        optionLabelProp.put("description",
                "Short label the user picks. 1-5 words ideal — this "
                        + "is what the user reads (and voice channels "
                        + "say aloud) as the choice text.");
        Map<String, Object> optionDescProp = new LinkedHashMap<>();
        optionDescProp.put("type", "string");
        optionDescProp.put("description",
                "Optional one-line clarification of what this option "
                        + "means / what happens when picked. Skipped "
                        + "when the label is self-explanatory.");
        optionItemProps.put("label", optionLabelProp);
        optionItemProps.put("description", optionDescProp);
        optionItemSchema.put("properties", optionItemProps);
        optionItemSchema.put("required", List.of("label"));

        Map<String, Object> optionsProp = new LinkedHashMap<>();
        optionsProp.put("type", "array");
        optionsProp.put("items", optionItemSchema);
        optionsProp.put("description",
                "ASK_USER-only: structured options for a multiple-"
                        + "choice question. Use when the answer set is "
                        + "small (2-4) and discrete (yes/no, A/B/C). "
                        + "Omit for open-ended questions. The user can "
                        + "always type free text instead of picking — "
                        + "the options are a UI shortcut, not a "
                        + "constraint.");

        Map<String, Object> intentProp = new LinkedHashMap<>();
        intentProp.put("type", "string");
        intentProp.put("description",
                "DISCOVER-only: the user-mentioned term / phrase / "
                        + "intent to look up in the Vance knowledge "
                        + "surface (manuals, skills, server tools). "
                        + "Required for DISCOVER. One short sentence "
                        + "or noun-phrase, e.g. \"frobnication summary\" "
                        + "or \"how to attach a calendar\". The engine "
                        + "runs the discovery synchronously and injects "
                        + "the result back so you can pick a downstream "
                        + "action with the result in hand.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typeProp);
        properties.put("reason", reasonProp);
        properties.put(PARAM_MESSAGE, messageProp);
        properties.put(PARAM_PRESET, presetProp);
        properties.put(PARAM_PROMPT, promptProp);
        properties.put(PARAM_EVENT_REF, eventRefProp);
        properties.put(PARAM_SCOPE, scopeProp);
        properties.put(PARAM_MODE, modeProp);
        properties.put(PARAM_CONTENT, learnContentProp);
        properties.put(PARAM_GOAL, goalProp);
        properties.put(PARAM_PLAN, planProp);
        properties.put(PARAM_SUMMARY, summaryProp);
        properties.put(PARAM_TODOS, todosProp);
        properties.put(PARAM_NOTES, notesProp);
        properties.put(PARAM_UPDATES, updatesProp);
        properties.put(PARAM_OPTIONS, optionsProp);
        properties.put(PARAM_INTENT, intentProp);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of("type", "reason"));
        return root;
    }
}
