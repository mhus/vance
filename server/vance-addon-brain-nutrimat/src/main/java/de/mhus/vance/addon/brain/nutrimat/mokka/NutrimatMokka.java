package de.mhus.vance.addon.brain.nutrimat.mokka;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.prak.HistoryStrengthFilter;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ClientTurnContextResolver;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code mokka} — predict before acting. Its own loop: the model calls
 * tools as long as it wants, the first message without a tool call is the
 * reply (janx semantics). On top, a turn-local protocol: every message that
 * calls tools states its expectation ({@code EXPECT: …}), and the message
 * after the tool results opens with the model's own verdict on it
 * ({@code MATCH: yes|no — why}). No judge — the model scores its own
 * prediction.
 *
 * <p>The loop counts hits and misses. A streak of
 * {@code params.mismatchThreshold} (default 2) misses in a row means the
 * model's picture of the situation is wrong: the loop forces a tool-less
 * reflection round, keeps the reflection in the context, reports it and
 * resets the streak. A tool round without an {@code EXPECT} line gets a
 * short reminder — never blocking. At the stop the prediction tally is
 * reported; a leading {@code MATCH:} line is cut from the reply.
 *
 * <p>Hypothesis: prediction errors are a confusion signal a hash comparison
 * does not deliver, and they catch dead ends earlier.
 *
 * <p>Nets: an opt-in round cap ({@code params.maxIterations}, default off)
 * and the per-turn wallclock ({@code params.maxWallclockMinutes}, default
 * 60) end the turn with the best partial text; a failed model call and an
 * empty reply end it as a failure. The interrupt comes from {@code round()}.
 * An answer leaves the process IDLE in both modes.
 */
@Component
@Slf4j
public class NutrimatMokka extends AbstractNutrimat {

    /** Recipe param: misses in a row that force a reflection round. */
    static final String PARAM_MISMATCH_THRESHOLD = "mismatchThreshold";

    /** Recipe param: opt-in round cap; absent / {@code 0} = no cap. */
    static final String PARAM_MAX_ITERATIONS = "maxIterations";

    /** Recipe param: per-turn wallclock net in minutes. */
    static final String PARAM_MAX_WALLCLOCK_MINUTES = "maxWallclockMinutes";

    static final int DEFAULT_MISMATCH_THRESHOLD = 2;
    static final int DEFAULT_WALLCLOCK_MINUTES = 60;

    /** The loop protocol — a turn-local system instruction, never persisted. */
    static final String PROTOCOL = "LOOP PROTOCOL (mokka): predict before you act.\n"
            + "- Every message in which you call tools must contain a line "
            + "`EXPECT: <what you expect the result to show>`.\n"
            + "- The first message after tool results must start with `MATCH: yes` or `MATCH: no`, "
            + "followed by one line on why the result did or did not match your expectation.\n"
            + "- When you are finished, reply without calling a tool — that text is your answer.";

    static final String EXPECT_REMINDER = "PROTOCOL (mokka): your last tool call had no `EXPECT:` line. "
            + "State the expected result before every tool call.";

    static final String REFLECTION_TEMPLATE = "REFLECTION (mokka): your predictions missed %d times in a row — "
            + "your picture of the situation is wrong. Without tools: what do you actually know, "
            + "which assumption failed, what will you try differently?";

    private static final Pattern EXPECT_LINE = Pattern.compile("(?im)^\\s*EXPECT:\\s*(\\S.*)$");

    private static final Pattern MATCH_LINE = Pattern.compile("(?i)^\\s*MATCH:\\s*(yes|no)\\b.*$");

    public NutrimatMokka(
            ThinkProcessService thinkProcessService,
            ObjectMapper objectMapper,
            StreamingProperties streamingProperties,
            ModelCatalog modelCatalog,
            LlmCallTracker llmCallTracker,
            MemoryContextLoader memoryContextLoader,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer composer,
            EngineChatFactory engineChatFactory,
            MemoryService memoryService,
            MemoryCompactionService memoryCompactionService,
            SkillResolver skillResolver,
            SkillPromptComposer skillPromptComposer,
            SkillTriggerMatcher skillTriggerMatcher,
            SessionService sessionService,
            PromptDateContextResolver promptDateContextResolver,
            ScratchpadPromptContributor scratchpadPromptContributor,
            WorkspaceService workspaceService,
            HistoryStrengthFilter historyStrengthFilter,
            ClientTurnContextResolver clientTurnContextResolver,
            TurnContextHandlerRegistry turnContextHandlers,
            ShootyGuardService guardService,
            NotificationService notifications) {
        super(
                thinkProcessService,
                objectMapper,
                streamingProperties,
                modelCatalog,
                llmCallTracker,
                memoryContextLoader,
                enginePromptResolver,
                composer,
                engineChatFactory,
                memoryService,
                memoryCompactionService,
                skillResolver,
                skillPromptComposer,
                skillTriggerMatcher,
                sessionService,
                promptDateContextResolver,
                scratchpadPromptContributor,
                workspaceService,
                historyStrengthFilter,
                clientTurnContextResolver,
                turnContextHandlers,
                guardService,
                notifications);
    }

    @Override
    protected String natureId() {
        return "mokka";
    }

    @Override
    protected String loopType() {
        return "predict before acting — every tool call states its expected result, "
                + "the loop tracks misses and forces reflection on a streak";
    }

    /** The opt-in round cap — {@code 0} = none (shown by {@code //nutrimat status}). */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return Math.max(0, paramInt(process, PARAM_MAX_ITERATIONS, 0));
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        in.messages().add(SystemMessage.from(PROTOCOL));
        int budget = iterationBudget(process);
        int threshold = Math.max(1, paramInt(process, PARAM_MISMATCH_THRESHOLD, DEFAULT_MISMATCH_THRESHOLD));
        int wallclockMinutes = Math.max(1, paramInt(process, PARAM_MAX_WALLCLOCK_MINUTES, DEFAULT_WALLCLOCK_MINUTES));
        Instant start = Instant.now();
        Tally tally = new Tally();
        String bestText = "";
        boolean afterToolRound = false;
        int rounds = 0;
        while (true) {
            if (budget > 0 && rounds >= budget) {
                narrate(ctx, process, "round cap reached after " + rounds + " rounds — ending with the best partial");
                report(process, tally.describe());
                return TurnOutcome.recovered(partialOr(
                        bestText,
                        "The run hit its round cap of " + budget + " (maxIterations) without producing an answer."));
            }
            if (Duration.between(start, Instant.now()).toMinutes() >= wallclockMinutes) {
                narrate(
                        ctx,
                        process,
                        "wallclock net reached after " + rounds + " rounds — ending with the best partial");
                report(process, tally.describe());
                return TurnOutcome.recovered(partialOr(
                        bestText,
                        "The run exceeded its " + wallclockMinutes
                                + "-minute wallclock budget without producing an answer."));
            }
            narrateRound(ctx, process, "round " + (rounds + 1) + " — " + tally.shortNote());
            AiMessage reply;
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return TurnOutcome.failed("The LLM call failed mid-loop: " + e.getMessage());
            }
            rounds++;
            stats.iterationsConsumed = rounds;
            String text = reply.text() == null ? "" : reply.text();
            if (text.length() > bestText.length()) {
                bestText = text;
            }

            if (afterToolRound) {
                Boolean match = matchOf(text);
                if (match != null) {
                    tally.score(match);
                    narrate(ctx, process, "prediction " + (match ? "hit" : "miss") + " — " + tally.shortNote());
                } else {
                    tally.unscored++;
                }
            }

            if (!reply.hasToolExecutionRequests()) {
                stats.stopCandidates++;
                if (text.isBlank()) {
                    return TurnOutcome.failed("The model returned an empty response (no text, no tool call).");
                }
                report(process, tally.describe());
                narrate(ctx, process, "stop: the model's text is the reply");
                return TurnOutcome.terminal(stripMatchLine(text), false);
            }

            appendInterimRoundText(ctx, process, text);
            dispatchTools(process, in, reply);
            afterToolRound = true;
            if (expectationOf(text) == null) {
                tally.missingExpect++;
                in.messages().add(SystemMessage.from(EXPECT_REMINDER));
                narrate(ctx, process, "tool round without EXPECT — reminder sent");
            } else {
                tally.predictions++;
            }

            if (tally.consecutiveMisses >= threshold) {
                // The reflection round counts as a round: it is a model call.
                String reflection = reflect(process, ctx, in, tally.consecutiveMisses);
                rounds++;
                stats.iterationsConsumed = rounds;
                if (!reflection.isBlank()) {
                    report(process, "reflection after " + tally.consecutiveMisses + " misses: " + reflection);
                }
                tally.consecutiveMisses = 0;
            }
        }
    }

    /**
     * The forced tool-less reflection round: the instruction and the
     * model's answer stay in the context, so the next working round builds
     * on the corrected picture.
     */
    private String reflect(ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, int misses) {
        narrate(ctx, process, "prediction streak: " + misses + " misses in a row — forcing a reflection round");
        in.messages().add(SystemMessage.from(String.format(Locale.ROOT, REFLECTION_TEMPLATE, misses)));
        AiMessage reply;
        try {
            reply = round(process, ctx, in.withToolSpecs(List.of()));
        } catch (NutrimatInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.info("Nutrimat[mokka] id='{}' reflection round failed: {}", process.getId(), e.toString());
            return "";
        }
        String text = reply.text() == null ? "" : reply.text().strip();
        if (text.isEmpty()) {
            return "";
        }
        // Text only — a tool call without its result would break the
        // message sequence; the round had no tools offered anyway.
        in.messages().add(AiMessage.from(text));
        appendInterimRoundText(ctx, process, text);
        return text;
    }

    /** The model's expectation in a tool round — {@code null} without an {@code EXPECT:} line. */
    static @Nullable String expectationOf(@Nullable String text) {
        if (text == null) return null;
        Matcher m = EXPECT_LINE.matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * The model's verdict on its last prediction: the first non-blank line
     * reads {@code MATCH: yes} / {@code MATCH: no}. {@code null} when the
     * message carries no verdict.
     */
    static @Nullable Boolean matchOf(@Nullable String text) {
        String first = firstNonBlankLine(text);
        if (first == null) return null;
        Matcher m = MATCH_LINE.matcher(first);
        if (!m.matches()) return null;
        return "yes".equalsIgnoreCase(m.group(1));
    }

    /**
     * Cuts a leading {@code MATCH:} line from the reply — it is protocol,
     * not answer. Only the first non-blank line; a reply that is nothing
     * but the verdict stays as it is.
     */
    static String stripMatchLine(String text) {
        String first = firstNonBlankLine(text);
        if (first == null || !MATCH_LINE.matcher(first).matches()) {
            return text;
        }
        int at = text.indexOf(first);
        String rest = text.substring(at + first.length()).strip();
        return rest.isEmpty() ? text : rest;
    }

    private static @Nullable String firstNonBlankLine(@Nullable String text) {
        if (text == null) return null;
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) return line;
        }
        return null;
    }

    private static String partialOr(String best, String fallback) {
        return best.isBlank() ? fallback : best;
    }

    /** Prediction bookkeeping for one turn. */
    static final class Tally {
        int predictions;
        int hits;
        int misses;
        int missingExpect;
        int unscored;
        int consecutiveMisses;

        void score(boolean match) {
            if (match) {
                hits++;
                consecutiveMisses = 0;
            } else {
                misses++;
                consecutiveMisses++;
            }
        }

        String shortNote() {
            return hits + " hit(s), " + misses + " miss(es)";
        }

        String describe() {
            return "predictions: " + hits + " hits, " + misses + " misses, " + missingExpect + " missing EXPECT";
        }
    }
}
