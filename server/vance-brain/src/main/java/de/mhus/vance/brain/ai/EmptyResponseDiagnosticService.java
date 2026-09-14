package de.mhus.vance.brain.ai;

import static java.util.Collections.emptySet;

import de.mhus.vance.brain.fook.FookService;
import de.mhus.vance.brain.fook.SubmissionRequest;
import de.mhus.vance.brain.fook.TicketContext;
import de.mhus.vance.brain.fook.TicketReporter;
import de.mhus.vance.brain.tools.BuiltInToolSource;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Post-mortem analysis for chat calls that exhausted their empty-completion
 * budget — the implementation behind {@link EmptyResponseDiagnosticSink}.
 *
 * <h2>The evidence problem</h2>
 * The symptom this class exists for surfaced on a vLLM-hosted model: the
 * provider silently dropped responses that followed a hallucinated tool
 * call. The tool name the model invented never arrives as data — no
 * {@code unknown tool} error, no tool-call request, just an empty body.
 * The only evidence is the request itself: the conversation text may
 * <i>name</i> a tool that is not in the offered {@code tools} array. The
 * candidate diff reconstructs exactly that.
 *
 * <h2>Classification</h2>
 * <ul>
 *   <li><b>Phantom tool call</b> — at least one tool name from the
 *       built-in inventory appears in the conversation text but was not
 *       offered. The {@code origin} says where the name entered the
 *       conversation: {@code prompt} (engine/recipe text taught a tool
 *       the recipe never added to {@code allowedToolsAdd} — a prompt
 *       bug), {@code user} (the user asked for a tool outside this
 *       surface) or {@code context} (history). Fire one WARN Megadodo
 *       row and — gated, deduplicated, re-armable — one Fook ticket.</li>
 *   <li><b>Genuine blank reply</b> — no candidate found. Still one WARN
 *       Megadodo row, so the case is countable, but no ticket: there is
 *       nothing actionable beyond the provider.</li>
 * </ul>
 *
 * <h2>Deduplication gate</h2>
 * A phantom call is deterministic — the same prompt, the same recipe,
 * the same model produce it again every turn. Without a gate that would
 * file one ticket per turn. The signature is the model label plus the
 * sorted candidate list; the marker row (tenant scope,
 * {@code ai.diagnostic.phantomToolCall.reported.<signature>}) carries the
 * last report instant. A new report for the same signature is allowed
 * only after the re-arm window (default 14 days, tenant-tunable via
 * {@code ai.diagnostic.phantomToolCall.reArmDays}).
 */
@Service
@Slf4j
public class EmptyResponseDiagnosticService {

    /** Reporter identity on Fook submissions — a system voice, not a user. */
    static final String SERVICE_ACCOUNT = "ai-diagnostics";

    static final String SETTING_RE_ARM_DAYS = "ai.diagnostic.phantomToolCall.reArmDays";
    static final String SETTING_REPORTED_PREFIX = "ai.diagnostic.phantomToolCall.reported.";

    static final int DEFAULT_RE_ARM_DAYS = 14;

    /** Origin label when the name came from engine/recipe text. */
    static final String ORIGIN_PROMPT = "prompt";
    /** Origin label when the name came from a user message. */
    static final String ORIGIN_USER = "user";
    /** Origin label when the name only appears in history/assistant text. */
    static final String ORIGIN_CONTEXT = "context";

    /**
     * A token that can carry a tool name: letters, digits, underscore.
     * Tool names are lowercase by convention; the collected text is
     * lowercased before matching — users write tool names in any
     * caps form, and a name is a name. Word boundaries (the token
     * char class itself) keep prose substring hits out.
     */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");

    /**
     * The known built-in tool names, computed once after boot — the
     * inventory is static. Volatile for the same lazy-init reasons as
     * any cached set: the first empty response may arrive on any
     * engine thread.
     */
    private volatile @Nullable Set<String> builtInNames;

    /**
     * Resolved lazily, not at injection time: the source aggregates
     * every {@link Tool} bean, and those beans sit at the far end of a
     * bean cycle (tools → engines → {@code EngineChatFactory} → this
     * service → tools). An {@link ObjectProvider} breaks the cycle
     * without behaviour change — the first {@link #diagnose} runs long
     * after the context is up.
     */
    private final ObjectProvider<BuiltInToolSource> builtInToolSource;

    private final MegadodoService megadodoService;
    private final FookService fookService;
    private final SettingService settingService;
    private final Clock clock;

    @Autowired
    public EmptyResponseDiagnosticService(
            ObjectProvider<BuiltInToolSource> builtInToolSource,
            MegadodoService megadodoService,
            FookService fookService,
            SettingService settingService) {
        this(builtInToolSource, megadodoService, fookService, settingService, Clock.systemUTC());
    }

    EmptyResponseDiagnosticService(
            BuiltInToolSource builtInToolSource,
            MegadodoService megadodoService,
            FookService fookService,
            SettingService settingService,
            Clock clock) {
        this(
                new ObjectProvider<>() {
                    @Override
                    public BuiltInToolSource getObject() {
                        return builtInToolSource;
                    }
                },
                megadodoService,
                fookService,
                settingService,
                clock);
    }

    private EmptyResponseDiagnosticService(
            ObjectProvider<BuiltInToolSource> builtInToolSource,
            MegadodoService megadodoService,
            FookService fookService,
            SettingService settingService,
            Clock clock) {
        this.builtInToolSource = builtInToolSource;
        this.megadodoService = megadodoService;
        this.fookService = fookService;
        this.settingService = settingService;
        this.clock = clock;
    }

    /**
     * The call site's scope — everything the engine layer knows and the
     * diagnosis needs to file rows, tickets and settings under the right
     * entities.
     */
    public record DiagnosticCall(
            String tenantId,
            @Nullable String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            String engineName) {}

    /**
     * Analyzes one exhausted chain of empty completions and reports it:
     * Megadodo always, Fook only for the phantom case, only when Fook is
     * enabled (submit throws when disabled — reporting surfaces
     * short-circuit) and only when the dedup gate allows it. Never throws
     * — a diagnostic that breaks the call it is reporting on would be
     * worse than no diagnostic.
     */
    public void onEmptyResponseExhausted(DiagnosticCall call, ChatRequest request, String modelLabel, int attempts) {
        try {
            Diagnosis diagnosis = diagnose(request);
            if (!diagnosis.candidates().isEmpty()) {
                megadodoService.phantomToolCallSuspected(
                        call.tenantId(),
                        call.projectId(),
                        call.processId(),
                        modelLabel,
                        diagnosis.candidates(),
                        diagnosis.origin(),
                        attempts);
                // Short-circuit before submit: FookService.submit guards
                // disabled state with an exception (defense-in-depth), and
                // burning the dedup marker for a ticket that cannot be
                // filed would silence the report for the whole re-arm
                // window. The Megadodo row above already fired either way.
                if (fookService.isEnabled() && gateAllows(call.tenantId(), modelLabel, diagnosis.candidates())) {
                    fookService.submit(SubmissionRequest.builder()
                            .text(evidence(call, request, modelLabel, attempts, diagnosis))
                            .reporter(TicketReporter.builder()
                                    .kind(TicketReporter.Kind.SERVICE_ACCOUNT)
                                    .serviceAccount(SERVICE_ACCOUNT)
                                    .build())
                            .context(TicketContext.builder()
                                    .projectId(call.projectId())
                                    .sessionId(call.sessionId())
                                    .processId(call.processId())
                                    .engine(call.engineName())
                                    .build())
                            .build());
                }
            } else {
                megadodoService.emptyModelResponse(
                        call.tenantId(), call.projectId(), call.processId(), modelLabel, attempts);
            }
        } catch (RuntimeException e) {
            log.warn("Empty-response diagnostics failed (reporting skipped): {}", e.getMessage(), e);
        }
    }

    /** One diagnosis outcome — the pure part of the analysis. */
    record Diagnosis(List<String> candidates, String origin, int offeredTools) {}

    /**
     * Computes the candidate diff: built-in tool names that appear in the
     * conversation text but are absent from the offered tools array, plus
     * the origin of the first (highest-priority) candidate found.
     */
    Diagnosis diagnose(ChatRequest request) {
        Set<String> offered = offeredToolNames(request);
        Set<String> systemTokens = new HashSet<>();
        Set<String> userTokens = new HashSet<>();
        Set<String> otherTokens = new HashSet<>();
        for (ChatMessage message : request.messages()) {
            Set<String> target = message instanceof SystemMessage
                    ? systemTokens
                    : message instanceof UserMessage ? userTokens : otherTokens;
            collectTokens(message, target);
        }

        List<String> candidates = new ArrayList<>();
        String origin = null;
        for (String name : builtInNames()) {
            if (offered.contains(name)) {
                continue;
            }
            if (systemTokens.contains(name)) {
                candidates.add(name);
                if (origin == null) {
                    origin = ORIGIN_PROMPT;
                }
            } else if (userTokens.contains(name)) {
                candidates.add(name);
                if (origin == null) {
                    origin = ORIGIN_USER;
                }
            } else if (otherTokens.contains(name)) {
                candidates.add(name);
                if (origin == null) {
                    origin = ORIGIN_CONTEXT;
                }
            }
        }
        // Sorted for stable signatures and readable rows: the inventory
        // iteration above is alphabetical already, but the contract should
        // not depend on the cache's iteration order.
        candidates.sort(String::compareTo);
        return new Diagnosis(candidates, origin != null ? origin : ORIGIN_CONTEXT, offered.size());
    }

    /**
     * Extracts the plain text of one message and collects its lowercase
     * tokens. System-message text, user text contents, assistant text and
     * tool results are the surfaces a tool name can be mentioned on.
     */
    private static void collectTokens(ChatMessage message, Set<String> target) {
        String text = plainText(message);
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            target.add(matcher.group());
        }
    }

    /** Plain text of a message across the message types Vance sends. */
    private static @Nullable String plainText(ChatMessage message) {
        if (message instanceof UserMessage user) {
            StringBuilder out = new StringBuilder();
            for (Content content : user.contents()) {
                if (content instanceof TextContent text) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(text.text());
                }
            }
            return out.length() == 0 ? null : out.toString();
        }
        if (message instanceof SystemMessage system) {
            return system.text();
        }
        if (message instanceof AiMessage ai) {
            return ai.text();
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return toolResult.text();
        }
        return null;
    }

    private static Set<String> offeredToolNames(ChatRequest request) {
        List<ToolSpecification> specs = request.toolSpecifications();
        if (specs == null || specs.isEmpty()) {
            return emptySet();
        }
        Set<String> names = new HashSet<>();
        for (ToolSpecification spec : specs) {
            names.add(spec.name());
        }
        return names;
    }

    private Set<String> builtInNames() {
        Set<String> names = builtInNames;
        if (names == null) {
            Set<String> computed = new TreeSet<>();
            for (var tool : builtInToolSource.getObject().list()) {
                // The token diff collects lowercase [a-z0-9_] tokens, so
                // the names it matches against are normalized the same
                // way. A name that still carries characters outside the
                // token class after lowercasing (dot, hyphen, …) can never
                // appear as a token — warn once instead of leaving the
                // name silently undetectable.
                String normalized = tool.name().toLowerCase(Locale.ROOT);
                if (!TOKEN.matcher(normalized).matches()) {
                    log.warn(
                            "Built-in tool name '{}' cannot be detected by the empty-response token diff "
                                    + "(characters outside [a-z0-9_] after lowercasing) — phantom tool calls "
                                    + "naming it will be reported as a genuine blank reply",
                            tool.name());
                }
                computed.add(normalized);
            }
            names = computed;
            builtInNames = names;
        }
        return names;
    }

    // ──────────────────── dedup gate ────────────────────

    /**
     * True when this signature has not been reported within the re-arm
     * window — and marks it as reported either way, so sequential
     * occurrences file at most one ticket per window. Concurrent
     * occurrences can race past the check once per window — see the
     * mark-before-submit note inside.
     */
    boolean gateAllows(String tenantId, String modelLabel, List<String> candidates) {
        String markerKey = SETTING_REPORTED_PREFIX + signature(modelLabel, candidates);
        String reported = settingService.getStringValueCascade(
                tenantId, /* projectId */ null, /* thinkProcessId */ null, markerKey);
        Instant now = clock.instant();
        if (reported != null && withinReArmWindow(tenantId, reported, now)) {
            return false;
        }
        // Mark before submitting: the window then holds even when the
        // submission below fails — a lost ticket is re-filed on the next
        // occurrence after the window, a duplicated one is not retractable.
        // The check-then-act is NOT atomic: two threads (or two pods,
        // the marker lives in the shared settings collection) can both
        // read an absent marker and both file. Best effort, accepted —
        // the window bounds the common sequential burst, and the worst
        // case of the race is one extra ticket per window, not a flood.
        settingService.setStringValue(
                tenantId,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                markerKey,
                now.toString());
        return true;
    }

    /** True when {@code reportedAt} + re-arm window lies in the future. */
    private boolean withinReArmWindow(String tenantId, String reported, Instant now) {
        Instant reportedAt;
        try {
            reportedAt = Instant.parse(reported);
        } catch (RuntimeException e) {
            // Unparseable marker (hand-edited, legacy value) re-arms
            // immediately: one extra ticket beats one lost report.
            return false;
        }
        return reportedAt.plus(reArmWindow(tenantId)).isAfter(now);
    }

    /**
     * Re-arm window from the tenant layer, default 14 days. Read at
     * tenant scope on purpose: this is an operator dial, not a
     * per-project or per-process behaviour.
     */
    private Duration reArmWindow(String tenantId) {
        String raw = settingService.getStringValueCascade(
                tenantId, /* projectId */ null, /* thinkProcessId */ null, SETTING_RE_ARM_DAYS);
        if (raw == null || raw.isBlank()) {
            return Duration.ofDays(DEFAULT_RE_ARM_DAYS);
        }
        try {
            long days = Long.parseLong(raw.trim());
            // Zero or negative: a deliberate "report every occurrence"
            // switch, not a parse error — honour it. Negative just falls
            // through to zero-length.
            return Duration.ofDays(Math.max(days, 0));
        } catch (NumberFormatException e) {
            log.warn(
                    "Setting '{}' is not a number (value: '{}') — using default {} days",
                    SETTING_RE_ARM_DAYS,
                    raw,
                    DEFAULT_RE_ARM_DAYS);
            return Duration.ofDays(DEFAULT_RE_ARM_DAYS);
        }
    }

    // ──────────────────── evidence ────────────────────

    /**
     * Short stable digest of the report signature: model label plus
     * candidate names, SHA-256, Base64url-truncated — keeps the setting
     * key bounded no matter how long the candidate list grows.
     */
    static String signature(String modelLabel, List<String> candidates) {
        String joined = modelLabel + "\n" + String.join("\n", candidates);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest)
                    .substring(0, 22);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java spec — unreachable.
            throw new IllegalStateException(e);
        }
    }

    /** Free-form evidence text for the Fook triage. */
    String evidence(DiagnosticCall call, ChatRequest request, String modelLabel, int attempts, Diagnosis diagnosis) {
        StringBuilder out = new StringBuilder();
        out.append("Empty model response after ")
                .append(attempts)
                .append(" attempt(s) — suspected phantom tool call.\n\n");
        out.append("Model: ").append(modelLabel).append('\n');
        out.append("Engine: ").append(call.engineName()).append('\n');
        out.append("Tenant: ").append(call.tenantId()).append('\n');
        if (call.projectId() != null) {
            out.append("Project: ").append(call.projectId()).append('\n');
        }
        if (call.sessionId() != null) {
            out.append("Session: ").append(call.sessionId()).append('\n');
        }
        if (call.processId() != null) {
            out.append("Process: ").append(call.processId()).append('\n');
        }
        out.append("Offered tools: ").append(diagnosis.offeredTools()).append('\n');
        out.append("Named in the conversation but not offered (origin: ")
                .append(diagnosis.origin())
                .append("): ")
                .append(String.join(", ", diagnosis.candidates()))
                .append("\n\n");
        out.append("The tool name never arrived as data — no unknown-tool error, ")
                .append("no tool-call request; it was reconstructed from the ")
                .append("request text against the tools array as sent. Likely ")
                .append("causes, in order of probability: a prompt teaches a tool ")
                .append("the recipe did not add (check the recipe's ")
                .append("allowedToolsAdd), the turn's tool-surface budget ")
                .append("demoted a tool that IS in the surface (check maxTools ")
                .append("and family demotion — the sent array is smaller than ")
                .append("the surface), the user asked for a tool outside this ")
                .append("surface, or history mentions a tool the current surface ")
                .append("no longer offers.");
        return out.toString();
    }
}
