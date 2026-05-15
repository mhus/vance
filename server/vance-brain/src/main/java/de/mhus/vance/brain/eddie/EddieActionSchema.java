package de.mhus.vance.brain.eddie;

import de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constants describing Eddie's structured-action vocabulary. Eddie is
 * the user-facing voice persona — Jarvis-like, "reads aloud" by
 * default — and the cross-project orchestrator that talks to Arthur
 * instances in worker projects. Her action set is wider than
 * Arthur's because she has two extra concerns: cross-project
 * delegation, and output-routing between voice (chat) and persistent
 * audit (inbox).
 *
 * <h2>Action types</h2>
 *
 * <ul>
 *   <li>{@link #TYPE_ANSWER} — direct reply, spoken.</li>
 *   <li>{@link #TYPE_ASK_USER} — clarification question to the user.</li>
 *   <li>{@link #TYPE_DELEGATE_PROJECT} — create a fresh worker
 *       project + initial steer for its Arthur.</li>
 *   <li>{@link #TYPE_STEER_PROJECT} — send a chat-input to an
 *       existing worker project's Arthur.</li>
 *   <li>{@link #TYPE_RELAY} — pass an Arthur reply through to the
 *       user as Eddie's voice (zero-token, engine copies content).
 *       Use this when the content is appropriate for being spoken.</li>
 *   <li>{@link #TYPE_RELAY_INBOX} — store an Arthur reply as a
 *       persistent inbox item AND post a short spoken note to the
 *       user. The {@code spoken} field is what reaches the chat;
 *       the worker's full content lands in the inbox. Use this when
 *       the user wants something kept for later, or when speaking
 *       the full content out loud doesn't fit the situation. Eddie
 *       judges this — there is no hard size threshold.</li>
 *   <li>{@link #TYPE_LEARN} — persist something about the user
 *       into Eddie's per-user memory. Two scopes: {@code persona}
 *       (how-to-talk-to-this-user summary, always in the prompt) and
 *       {@code fact} (specific facts — birthday, preferences,
 *       dislikes — appended to a journal).</li>
 *   <li>{@link #TYPE_WAIT} — async work in flight, nothing to add.</li>
 *   <li>{@link #TYPE_REJECT} — out of scope, explain briefly.</li>
 * </ul>
 *
 * <p>Every action carries a non-blank {@code reason} (validated by
 * {@code StructuredActionEngine}) — both for audit and to force the
 * model to think about <em>why</em> it picked this branch.
 */
public final class EddieActionSchema {

    private EddieActionSchema() {}

    public static final String TOOL_NAME = "eddie_action";

    public static final String TOOL_DESCRIPTION =
            "Final structured action for this turn. Call this exactly once "
                    + "per turn (after any read-only tool calls). The 'type' "
                    + "picks the branch, 'reason' explains the choice, and "
                    + "per-type fields carry the content. Replaces the "
                    + "free-form combination of project_create / "
                    + "project_chat_send / inbox_post / respond.";

    public static final String TYPE_ANSWER           = "ANSWER";
    public static final String TYPE_ASK_USER         = "ASK_USER";
    public static final String TYPE_DELEGATE_PROJECT = "DELEGATE_PROJECT";
    public static final String TYPE_STEER_PROJECT    = "STEER_PROJECT";
    public static final String TYPE_RELAY            = "RELAY";
    public static final String TYPE_RELAY_INBOX      = "RELAY_INBOX";
    public static final String TYPE_LEARN            = "LEARN";
    public static final String TYPE_MEDIATE          = "MEDIATE";
    public static final String TYPE_WAIT             = "WAIT";
    public static final String TYPE_REJECT           = "REJECT";

    /**
     * Eddie's own action types — used for the schema enum and the
     * structured-action validator. Plan-Mode types from
     * {@link PlanModeActionSchema#ALL_TYPES} are unioned into
     * {@link #SUPPORTED_TYPES} below; they're routed by
     * {@code EddieEngine.handleAction(...)} through
     * {@code PlanModeService.dispatch(...)} before the Eddie-specific
     * switch runs.
     */
    public static final Set<String> EDDIE_OWN_TYPES = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER,
            TYPE_DELEGATE_PROJECT, TYPE_STEER_PROJECT,
            TYPE_RELAY, TYPE_RELAY_INBOX,
            TYPE_LEARN,
            TYPE_MEDIATE,
            TYPE_WAIT, TYPE_REJECT);

    /**
     * Full set the structured-action validator accepts: Eddie's own
     * types plus the shared Plan-Mode types. The order is preserved
     * via {@link LinkedHashSet} so the schema enum stays deterministic.
     */
    public static final Set<String> SUPPORTED_TYPES = buildSupportedTypes();

    private static Set<String> buildSupportedTypes() {
        Set<String> all = new LinkedHashSet<>(EDDIE_OWN_TYPES);
        all.addAll(PlanModeActionSchema.ALL_TYPES);
        return Set.copyOf(all);
    }

    /** LEARN scope: persistent persona summary, always loaded into Eddie's prompt. */
    public static final String LEARN_SCOPE_PERSONA = "persona";

    /** LEARN scope: factual journal entry, appended with timestamp. */
    public static final String LEARN_SCOPE_FACT = "fact";

    public static final Set<String> LEARN_SCOPES = Set.of(
            LEARN_SCOPE_PERSONA, LEARN_SCOPE_FACT);

    public static final String LEARN_MODE_APPEND  = "append";
    public static final String LEARN_MODE_REPLACE = "replace";

    // ─────────────────────────────────────────────
    // Field names — public so handlers can use them
    // ─────────────────────────────────────────────

    /** User-facing message text (ANSWER / ASK_USER / REJECT / WAIT-with-note / RELAY-prefix). */
    public static final String PARAM_MESSAGE      = "message";

    /** Project name to create (DELEGATE_PROJECT) — should be a slug-style identifier. */
    public static final String PARAM_PROJECT_NAME = "projectName";

    /** Optional human-readable title for a freshly created project. */
    public static final String PARAM_PROJECT_TITLE = "projectTitle";

    /** Initial instruction handed to the worker project's Arthur on spawn (DELEGATE_PROJECT). */
    public static final String PARAM_PROJECT_GOAL = "projectGoal";

    /** Optional kit name from the tenant project-kits catalog, installed at spawn (DELEGATE_PROJECT). */
    public static final String PARAM_KIT_NAME = "kitName";

    /** Existing project to steer (STEER_PROJECT) — name or id. */
    public static final String PARAM_PROJECT      = "project";

    /** Content to send to the existing project's Arthur (STEER_PROJECT). */
    public static final String PARAM_CONTENT      = "content";

    /** Worker process name whose last reply to relay (RELAY / RELAY_INBOX). */
    public static final String PARAM_SOURCE       = "source";

    /** Optional short prefix prepended to relayed worker text (RELAY). */
    public static final String PARAM_PREFIX       = "prefix";

    /** Inbox item title (RELAY_INBOX) — what the user sees in the inbox list. */
    public static final String PARAM_INBOX_TITLE  = "inboxTitle";

    /** Short spoken-style chat message that announces the inbox item (RELAY_INBOX). */
    public static final String PARAM_SPOKEN       = "spoken";

    /** LEARN scope discriminator: {@code persona} or {@code fact}. */
    public static final String PARAM_SCOPE        = "scope";

    /** LEARN persona-update mode: {@code append} or {@code replace} (default). */
    public static final String PARAM_MODE         = "mode";

    /**
     * Schema (flat) covering all action types. Per-type required-field
     * validation lives in {@code EddieEngine.handleAction}, where the
     * type is known. Top-level {@code type} and {@code reason} are
     * required by the JSON schema; everything else is "optional in the
     * schema, required in the handler for the right type" — same
     * pattern as Arthur. Keeps the schema from exploding into
     * {@code anyOf} variants that some LLMs handle poorly.
     */
    public static Map<String, Object> schema() {
        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("enum", List.of(
                TYPE_ANSWER, TYPE_ASK_USER,
                TYPE_DELEGATE_PROJECT, TYPE_STEER_PROJECT,
                TYPE_RELAY, TYPE_RELAY_INBOX,
                TYPE_LEARN,
                TYPE_MEDIATE,
                TYPE_WAIT, TYPE_REJECT,
                // Plan-Mode types — handled by the shared PlanModeService.
                PlanModeActionSchema.TYPE_START_PLAN,
                PlanModeActionSchema.TYPE_PROPOSE_PLAN,
                PlanModeActionSchema.TYPE_START_EXECUTION,
                PlanModeActionSchema.TYPE_TODO_UPDATE));
        typeProp.put("description",
                "Which branch this turn takes. ANSWER = direct spoken "
                        + "reply. ASK_USER = clarification question. "
                        + "DELEGATE_PROJECT = create a fresh worker project "
                        + "and steer its Arthur. STEER_PROJECT = send "
                        + "chat-input to an existing project's Arthur. "
                        + "RELAY = read a worker reply aloud as your voice. "
                        + "RELAY_INBOX = save a worker reply to the user's "
                        + "inbox + announce it briefly. LEARN = persist "
                        + "something about the user (persona summary or "
                        + "specific fact) into per-user memory. MEDIATE = "
                        + "hand the user-WS over to a worker session for a "
                        + "direct conversation (use when the user needs "
                        + "client-side tools the worker has but Eddie can't "
                        + "route — see eddie-engine §8.5). WAIT = async work "
                        + "running. REJECT = out of scope. "
                        + "START_PLAN = enter EXPLORING mode for a non-"
                        + "trivial task that benefits from explore-then-"
                        + "plan-then-execute (rarely needed in Eddie's "
                        + "orchestrator role — usually DELEGATE_PROJECT "
                        + "or STEER_PROJECT to a worker is the better "
                        + "match). PROPOSE_PLAN / START_EXECUTION / "
                        + "TODO_UPDATE — see specification/plan-mode.md.");

        Map<String, Object> reasonProp = new LinkedHashMap<>();
        reasonProp.put("type", "string");
        reasonProp.put("description",
                "One short sentence explaining why this action was chosen. "
                        + "Required for audit and to force deliberate decisions.");

        Map<String, Object> messageProp = new LinkedHashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description",
                "User-facing chat text (spoken-friendly). Required for "
                        + "ANSWER, ASK_USER, REJECT. Optional for WAIT (only "
                        + "when you have something to say). Markdown is "
                        + "discouraged — Eddie reads aloud, prose works "
                        + "better than lists or headers.");

        Map<String, Object> projectNameProp = new LinkedHashMap<>();
        projectNameProp.put("type", "string");
        projectNameProp.put("description",
                "Project name to create (slug-style, lowercase, hyphenated). "
                        + "Required for DELEGATE_PROJECT.");

        Map<String, Object> projectTitleProp = new LinkedHashMap<>();
        projectTitleProp.put("type", "string");
        projectTitleProp.put("description",
                "Optional human-readable project title. DELEGATE_PROJECT only.");

        Map<String, Object> projectGoalProp = new LinkedHashMap<>();
        projectGoalProp.put("type", "string");
        projectGoalProp.put("description",
                "Initial instruction handed to the worker project's Arthur "
                        + "on spawn — self-contained, the worker doesn't see "
                        + "your chat history. Required for DELEGATE_PROJECT.");

        Map<String, Object> kitNameProp = new LinkedHashMap<>();
        kitNameProp.put("type", "string");
        kitNameProp.put("description",
                "Optional kit to install into the freshly created project "
                        + "(DELEGATE_PROJECT only). Must be a name from the "
                        + "tenant project-kits catalog — call kit_list first "
                        + "to see what's available. The kit installs in the "
                        + "same request as the project create, so the "
                        + "worker's Arthur starts with the kit's documents, "
                        + "settings and tools already mounted. Omit for a "
                        + "bare project.");

        Map<String, Object> projectProp = new LinkedHashMap<>();
        projectProp.put("type", "string");
        projectProp.put("description",
                "Existing project name (or id) to steer. Required for "
                        + "STEER_PROJECT. Use the name from project_list, "
                        + "not a guess.");

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description",
                "For STEER_PROJECT: chat-input to send to the existing "
                        + "project's Arthur. For LEARN: the persona "
                        + "update or factual entry to persist. Required "
                        + "for both.");

        Map<String, Object> sourceProp = new LinkedHashMap<>();
        sourceProp.put("type", "string");
        sourceProp.put("description",
                "Worker process name (or id) whose last reply should be "
                        + "relayed. Required for RELAY and RELAY_INBOX. Use "
                        + "the sourceProcessName from the most recent "
                        + "<process-event> marker, not a guess.");

        Map<String, Object> prefixProp = new LinkedHashMap<>();
        prefixProp.put("type", "string");
        prefixProp.put("description",
                "Optional short spoken-style line before the relayed text "
                        + "(RELAY only). Leave empty for clean pass-through.");

        Map<String, Object> inboxTitleProp = new LinkedHashMap<>();
        inboxTitleProp.put("type", "string");
        inboxTitleProp.put("description",
                "Title for the inbox item — what the user sees in the list. "
                        + "Required for RELAY_INBOX. Short and descriptive.");

        Map<String, Object> spokenProp = new LinkedHashMap<>();
        spokenProp.put("type", "string");
        spokenProp.put("description",
                "Short voice-friendly chat message announcing that the "
                        + "content has been put in the inbox. One or two "
                        + "sentences, prose, no Markdown. Required for "
                        + "RELAY_INBOX. The user hears this; the long "
                        + "content goes silently to the inbox.");

        Map<String, Object> scopeProp = new LinkedHashMap<>();
        scopeProp.put("type", "string");
        scopeProp.put("enum", List.of(LEARN_SCOPE_PERSONA, LEARN_SCOPE_FACT));
        scopeProp.put("description",
                "LEARN scope. 'persona' = how-to-talk-to-this-user "
                        + "summary, always loaded into Eddie's prompt "
                        + "(use for persona traits, communication style, "
                        + "preferences about Eddie's behavior). 'fact' = "
                        + "a specific user fact (birthday, favorite "
                        + "color, dislike, hobby) appended to the journal. "
                        + "Required for LEARN.");

        Map<String, Object> modeProp = new LinkedHashMap<>();
        modeProp.put("type", "string");
        modeProp.put("enum", List.of(LEARN_MODE_APPEND, LEARN_MODE_REPLACE));
        modeProp.put("description",
                "LEARN persona update mode. 'replace' (default) overwrites "
                        + "the entire persona summary — use when you want "
                        + "a clean rewrite. 'append' adds to the end — use "
                        + "when adding a new note without disturbing the "
                        + "existing summary. Ignored for scope=fact "
                        + "(facts are always appended).");

        // Plan-Mode property descriptors. Same descriptions as Arthur's
        // schema — Eddie reuses the shared semantics. The fields are
        // optional in the schema (per-type required-field validation
        // sits in PlanModeService), so any unused property only adds a
        // few bytes to the LLM's tool spec.
        Map<String, Object> planGoalProp = new LinkedHashMap<>();
        planGoalProp.put("type", "string");
        planGoalProp.put("description",
                "Optional one-liner restating the task. Used for START_PLAN.");

        Map<String, Object> planProp = new LinkedHashMap<>();
        planProp.put("type", "string");
        planProp.put("description",
                "Markdown plan text. Required for PROPOSE_PLAN.");

        Map<String, Object> planSummaryProp = new LinkedHashMap<>();
        planSummaryProp.put("type", "string");
        planSummaryProp.put("description",
                "One-line summary of the plan. Required for PROPOSE_PLAN.");

        Map<String, Object> todoItemSchema = new LinkedHashMap<>();
        todoItemSchema.put("type", "object");
        Map<String, Object> todoItemProps = new LinkedHashMap<>();
        Map<String, Object> todoIdProp = new LinkedHashMap<>();
        todoIdProp.put("type", "string");
        todoIdProp.put("description", "Stable id within the plan.");
        Map<String, Object> todoContentProp = new LinkedHashMap<>();
        todoContentProp.put("type", "string");
        todoContentProp.put("description", "Imperative form of the todo.");
        Map<String, Object> todoActiveFormProp = new LinkedHashMap<>();
        todoActiveFormProp.put("type", "string");
        todoActiveFormProp.put("description", "Optional present-continuous.");
        todoItemProps.put("id", todoIdProp);
        todoItemProps.put("content", todoContentProp);
        todoItemProps.put("activeForm", todoActiveFormProp);
        todoItemSchema.put("properties", todoItemProps);
        todoItemSchema.put("required", List.of("id", "content"));

        Map<String, Object> todosProp = new LinkedHashMap<>();
        todosProp.put("type", "array");
        todosProp.put("items", todoItemSchema);
        todosProp.put("description",
                "TodoList: 3–8 plan steps. Required for PROPOSE_PLAN.");

        Map<String, Object> planNotesProp = new LinkedHashMap<>();
        planNotesProp.put("type", "string");
        planNotesProp.put("description",
                "Optional extra context from the user's approval. "
                        + "Used for START_EXECUTION.");

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
                "TodoItem status updates. Required for TODO_UPDATE.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typeProp);
        properties.put("reason", reasonProp);
        properties.put(PARAM_MESSAGE, messageProp);
        properties.put(PARAM_PROJECT_NAME, projectNameProp);
        properties.put(PARAM_PROJECT_TITLE, projectTitleProp);
        properties.put(PARAM_PROJECT_GOAL, projectGoalProp);
        properties.put(PARAM_KIT_NAME, kitNameProp);
        properties.put(PARAM_PROJECT, projectProp);
        properties.put(PARAM_CONTENT, contentProp);
        properties.put(PARAM_SOURCE, sourceProp);
        properties.put(PARAM_PREFIX, prefixProp);
        properties.put(PARAM_INBOX_TITLE, inboxTitleProp);
        properties.put(PARAM_SPOKEN, spokenProp);
        properties.put(PARAM_SCOPE, scopeProp);
        properties.put(PARAM_MODE, modeProp);
        // Plan-Mode params from PlanModeActionSchema.
        properties.put(PlanModeActionSchema.PARAM_GOAL, planGoalProp);
        properties.put(PlanModeActionSchema.PARAM_PLAN, planProp);
        properties.put(PlanModeActionSchema.PARAM_SUMMARY, planSummaryProp);
        properties.put(PlanModeActionSchema.PARAM_TODOS, todosProp);
        properties.put(PlanModeActionSchema.PARAM_NOTES, planNotesProp);
        properties.put(PlanModeActionSchema.PARAM_UPDATES, updatesProp);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of("type", "reason"));
        return root;
    }
}
