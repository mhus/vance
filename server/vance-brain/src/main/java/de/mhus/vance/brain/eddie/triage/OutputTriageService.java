package de.mhus.vance.brain.eddie.triage;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Pre-classifies worker output before Eddie's Channel-Adapter routes it
 * to the user — see {@code planning/eddie-moderator-erweiterung.md}.
 *
 * <h2>Two-stage decision</h2>
 *
 * <ol>
 *   <li><b>Heuristic</b> (this class): handles the obvious cases
 *       deterministically — short prose passes through verbatim, long
 *       output goes to the inbox, code/Markdown is gated against
 *       voice. The plan doc estimates 70-80% of frames terminate
 *       here.</li>
 *   <li><b>LLM</b> (future, behind the same {@link #classify}
 *       interface): only consulted when the heuristic returns
 *       {@link TriageDecision#REFORMULATE} on a non-trivial input —
 *       it produces a better {@code memorySummary} /
 *       {@code spokenAnnouncement} and may downgrade decision/
 *       criticality. v1 ships with the heuristic only; the LLM step
 *       lands later.</li>
 * </ol>
 *
 * <h2>Hard rules</h2>
 *
 * <ul>
 *   <li>{@link Criticality#CRITICAL} forbids {@link TriageDecision#REFORMULATE}.
 *       Plan-approvals, delete-confirmations and similar must reach
 *       the user verbatim or via inbox — never paraphrased. The
 *       heuristic doesn't elevate to {@code CRITICAL} on its own; the
 *       LLM step does, and the rule clamps the final decision either
 *       way.</li>
 *   <li>Markdown / fenced code blocks / unified diffs / explicit JSON
 *       are <b>never</b> {@code VERBATIM} in voice mode — TTS would
 *       read the punctuation aloud. The heuristic forces INBOX or
 *       REFORMULATE.</li>
 *   <li>A worker-supplied {@code outputHint} short-circuits the
 *       heuristic (the worker knows its own output best).</li>
 * </ul>
 */
@Service
@Slf4j
public class OutputTriageService {

    private final @Nullable LlmTriageStage llmStage;

    /**
     * Spring constructor — {@link LlmTriageStage} is optional; without
     * a registered bean the service is heuristic-only.
     */
    @Autowired
    public OutputTriageService(
            @Autowired(required = false) @Nullable LlmTriageStage llmStage) {
        this.llmStage = llmStage;
    }

    /** No-arg constructor for tests / heuristic-only callers. */
    public OutputTriageService() {
        this(null);
    }

    /** Voice-mode upper bound for VERBATIM (chars). */
    static final int VOICE_VERBATIM_MAX = 60;
    /** Voice-mode lower bound for INBOX (chars). */
    static final int VOICE_INBOX_MIN = 400;
    /** Text-mode upper bound for VERBATIM (chars). */
    static final int TEXT_VERBATIM_MAX = 200;
    /** Text-mode lower bound for INBOX (chars). */
    static final int TEXT_INBOX_MIN = 2000;

    /** Triple-backtick fenced code block. */
    private static final Pattern FENCED_CODE = Pattern.compile("```");
    /** Unified-diff hunk header. */
    private static final Pattern DIFF_HUNK = Pattern.compile("(?m)^@@ ");
    /** Markdown header line. */
    private static final Pattern MD_HEADER = Pattern.compile("(?m)^#{1,6} ");
    /** Markdown bullet / numbered list. */
    private static final Pattern MD_LIST = Pattern.compile("(?m)^([*+\\-]|\\d+\\.) ");
    /** JSON object or array as the entire payload. */
    private static final Pattern JSON_BODY = Pattern.compile("(?s)^\\s*[{\\[].*[}\\]]\\s*$");

    /**
     * Classifies a worker frame, heuristic-only. Never returns
     * {@code null}. Pure-function shape — same input → same result.
     *
     * <p>The Channel-Adapter calls this when no Eddie process context
     * is available (e.g. the unit-test path). When context is known,
     * use {@link #classifyWithContext} to engage the LLM stage on
     * mid-length cases.
     */
    public TriageResult classify(TriageInput input) {
        String hint = input.normalisedHint();
        if (hint != null) {
            return fromHint(hint, input);
        }
        return heuristic(input);
    }

    /**
     * Classifies a worker frame with optional LLM refinement.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Heuristic stage (the trivial 70-80% — VERBATIM short prose,
     *       INBOX over-the-cutoff, hint-respect).</li>
     *   <li><b>Iff</b> the heuristic returns
     *       {@link TriageDecision#REFORMULATE} <b>and</b> an
     *       {@link LlmTriageStage} bean is registered: refine via the
     *       LLM. The stage may upgrade decision / criticality and
     *       produce a real {@code memorySummary} +
     *       {@code spokenAnnouncement}.</li>
     *   <li>Hard-override clamp (CRITICAL+REFORMULATE → INBOX) on the
     *       final result.</li>
     * </ol>
     *
     * <p>On any LLM stage exception we degrade to the heuristic — the
     * tradeoff is „better summary nice-to-have, never block the
     * pipeline".
     */
    public TriageResult classifyWithContext(TriageInput input, ThinkProcessDocument context) {
        TriageResult heuristic = classify(input);
        if (llmStage == null
                || heuristic.decision() != TriageDecision.REFORMULATE
                || context == null) {
            return applyHardOverrides(heuristic, input);
        }
        try {
            TriageResult refined = llmStage.refine(input, heuristic, context);
            return applyHardOverrides(refined == null ? heuristic : refined, input);
        } catch (RuntimeException e) {
            log.debug("LLM triage stage failed, falling back to heuristic: {}", e.toString());
            return applyHardOverrides(heuristic, input);
        }
    }

    private TriageResult fromHint(String hint, TriageInput input) {
        return switch (hint) {
            case "VERBATIM" -> new TriageResult(
                    TriageDecision.VERBATIM, Criticality.NORMAL,
                    null, summarise(input));
            case "INBOX" -> new TriageResult(
                    TriageDecision.INBOX, Criticality.NORMAL,
                    spokenForInbox(input), summarise(input));
            case "FREE" -> heuristic(input); // worker explicitly leaves it open
            default -> {
                log.debug("OutputTriage: unknown hint '{}', falling back to heuristic", hint);
                yield heuristic(input);
            }
        };
    }

    private TriageResult heuristic(TriageInput input) {
        String text = input.text() == null ? "" : input.text();
        int len = text.length();
        boolean structural = looksStructural(text);

        if (input.voiceMode()) {
            if (structural) {
                // Voice can't read Markdown / code aloud — never verbatim.
                return new TriageResult(
                        TriageDecision.INBOX, Criticality.NORMAL,
                        spokenForInbox(input), summarise(input));
            }
            if (len <= VOICE_VERBATIM_MAX) {
                return new TriageResult(
                        TriageDecision.VERBATIM, Criticality.LOW,
                        null, summarise(input));
            }
            if (len >= VOICE_INBOX_MIN) {
                return new TriageResult(
                        TriageDecision.INBOX, Criticality.NORMAL,
                        spokenForInbox(input), summarise(input));
            }
            return new TriageResult(
                    TriageDecision.REFORMULATE, Criticality.NORMAL,
                    null, summarise(input));
        }

        // Text mode — looser thresholds. Code/Markdown isn't toxic
        // here, but we still steer it away from REFORMULATE so
        // structure isn't lost in paraphrase.
        if (structural) {
            if (len >= TEXT_INBOX_MIN) {
                return new TriageResult(
                        TriageDecision.INBOX, Criticality.NORMAL,
                        spokenForInbox(input), summarise(input));
            }
            return new TriageResult(
                    TriageDecision.VERBATIM, Criticality.NORMAL,
                    null, summarise(input));
        }
        if (len <= TEXT_VERBATIM_MAX) {
            return new TriageResult(
                    TriageDecision.VERBATIM, Criticality.LOW,
                    null, summarise(input));
        }
        if (len >= TEXT_INBOX_MIN) {
            return new TriageResult(
                    TriageDecision.INBOX, Criticality.NORMAL,
                    spokenForInbox(input), summarise(input));
        }
        return new TriageResult(
                TriageDecision.REFORMULATE, Criticality.NORMAL,
                null, summarise(input));
    }

    /**
     * Final-gate clamp: refuse {@link TriageDecision#REFORMULATE} when
     * criticality is {@link Criticality#CRITICAL}. Re-applied even on
     * results coming back from a future LLM step. Falls back to
     * {@link TriageDecision#INBOX} (safer choice — full text preserved
     * + audit trail).
     */
    public TriageResult applyHardOverrides(TriageResult result, TriageInput input) {
        if (result.criticality() == Criticality.CRITICAL
                && result.decision() == TriageDecision.REFORMULATE) {
            log.warn("OutputTriage: hard-override CRITICAL+REFORMULATE -> INBOX");
            return new TriageResult(
                    TriageDecision.INBOX,
                    Criticality.CRITICAL,
                    result.spokenAnnouncement() != null
                            ? result.spokenAnnouncement()
                            : spokenForInbox(input),
                    result.memorySummary());
        }
        return result;
    }

    /**
     * Heuristic for "this isn't plain prose": triple-backtick code
     * fence, unified-diff hunk header, Markdown header / list, or a
     * JSON body framing the whole payload.
     */
    static boolean looksStructural(String text) {
        if (text == null || text.isEmpty()) return false;
        return FENCED_CODE.matcher(text).find()
                || DIFF_HUNK.matcher(text).find()
                || MD_HEADER.matcher(text).find()
                || MD_LIST.matcher(text).find()
                || JSON_BODY.matcher(text).matches();
    }

    /**
     * Heuristic memory-summary: first line clamped to ~120 chars.
     * The LLM step replaces this with a real summary later; until
     * then this gives Eddie at least the gist when she renders the
     * {@code <delegated_workers>} prompt block.
     */
    private static @Nullable String summarise(TriageInput input) {
        String text = input.text();
        if (text == null || text.isBlank()) return null;
        String firstLine = text.lines().findFirst().orElse(text).strip();
        if (firstLine.isEmpty()) return null;
        return firstLine.length() <= 120 ? firstLine : firstLine.substring(0, 120) + "…";
    }

    /**
     * Heuristic spoken-announcement default for the INBOX path. The
     * LLM step will replace this with something more specific
     * (e.g. "Arthur hat einen Plan vorgelegt" rather than a generic
     * "Antwort"). Keeps Eddie's voice line non-empty in the
     * heuristic-only mode.
     */
    private static String spokenForInbox(TriageInput input) {
        String engine = input.workerEngine();
        if (engine == null || engine.isBlank()) {
            return "Antwort liegt in der Inbox.";
        }
        return engine + " hat geantwortet — liegt in der Inbox.";
    }
}
