package de.mhus.vance.brain.hactar.phases;

import static de.mhus.vance.brain.hactar.phases.HactarContextRenderer.paramString;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * REVIEWING — spawns the configured reviewer sub-recipe as a child
 * process, hands it the plan sketch as steer content, awaits the
 * reply synchronously (Zaphod-style drive pattern), and parses a
 * VERDICT line (APPROVED / REJECTED).
 *
 * <p>Verdict-parsing is lenient: the first line containing
 * {@code APPROVED} or {@code REJECTED} (case-insensitive) wins.
 * Unparseable replies default to REJECTED so the loop runs once
 * more rather than silently advancing.
 *
 * <p>If no reviewer recipe is configured AND no
 * {@code <parentRecipe>-reviewer} resolves: REVIEWING is skipped
 * ({@code reviewerVerdict = "SKIPPED"}) and the engine transitions to
 * DRAFTING with the unreviewed plan sketch as context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewingPhase {

    /** Explicit reviewer sub-recipe name. When unset, fall back to
     *  {@code <parentRecipe>-reviewer} from the spawning recipe. */
    public static final String REVIEWER_RECIPE_KEY = "reviewerRecipe";

    private final ThinkProcessService thinkProcessService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    public HactarStatus execute(
            HactarState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String reviewerRecipe = resolveReviewerRecipe(process);
        if (reviewerRecipe == null) {
            log.info("Hactar.runReviewing id='{}' no reviewer recipe "
                            + "configured/resolvable — skipping review",
                    process.getId());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("No reviewer recipe configured.");
            return HactarStatus.DRAFTING;
        }

        AppliedRecipe applied;
        try {
            applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), reviewerRecipe,
                    process.getConnectionProfile(), null);
        } catch (RecipeResolver.UnknownRecipeException ure) {
            log.warn("Hactar.runReviewing id='{}' reviewer recipe '{}' "
                            + "unknown — skipping review",
                    process.getId(), reviewerRecipe);
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes(
                    "Reviewer recipe '" + reviewerRecipe + "' not found.");
            return HactarStatus.DRAFTING;
        }

        ThinkEngineService engineService = thinkEngineServiceProvider.getObject();
        ThinkEngine targetEngine = engineService.resolve(applied.engine())
                .orElseThrow(() -> new IllegalStateException(
                        "Reviewer recipe '" + applied.name()
                                + "' references unknown engine '"
                                + applied.engine() + "'"));

        String childName = "hactar-reviewer-" + process.getId()
                + "-" + (state.getFramingRecoveryCount() + 1);
        ThinkProcessDocument child;
        try {
            child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(),
                    targetEngine.version(),
                    "Hactar plan reviewer for " + process.getId(),
                    process.getGoal(),
                    process.getId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : Set.copyOf(applied.allowedSkills()));
            engineService.start(child);
        } catch (RuntimeException e) {
            log.warn("Hactar.runReviewing id='{}' spawn failed: {} — "
                            + "treating as SKIPPED, advancing to DRAFTING",
                    process.getId(), e.toString());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("Reviewer spawn failed: " + e.getMessage());
            return HactarStatus.DRAFTING;
        }

        String reply;
        try {
            driveReviewerTurn(child, process.getId(), buildReviewerSteerContent(state));
            reply = readLastAssistantText(
                    process.getTenantId(), process.getSessionId(), child.getId());
        } catch (RuntimeException e) {
            log.warn("Hactar.runReviewing id='{}' drive failed: {} — "
                            + "treating as SKIPPED",
                    process.getId(), e.toString());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("Reviewer drive failed: " + e.getMessage());
            cleanupReviewerChild(child);
            return HactarStatus.DRAFTING;
        }
        cleanupReviewerChild(child);

        if (reply == null || reply.isBlank()) {
            log.warn("Hactar.runReviewing id='{}' reviewer produced no "
                            + "reply — treating as SKIPPED",
                    process.getId());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("Reviewer produced no reply.");
            return HactarStatus.DRAFTING;
        }

        ReviewerVerdict parsed = parseVerdict(reply);
        state.setReviewerVerdict(parsed.verdict());
        state.setReviewerNotes(reply.trim());
        log.info("Hactar.runReviewing id='{}' verdict={} reply chars={}",
                process.getId(), parsed.verdict(), reply.length());

        if ("APPROVED".equals(parsed.verdict())) {
            return HactarStatus.DRAFTING;
        }

        state.setFramingRecoveryCount(state.getFramingRecoveryCount() + 1);
        if (state.getFramingRecoveryCount() >= state.getMaxFramingRecoveries()) {
            state.setFailureReason(
                    "Exceeded maxFramingRecoveries ("
                            + state.getMaxFramingRecoveries()
                            + ") — last reviewer critique: "
                            + abbreviateForReason(reply));
            return HactarStatus.FAILED;
        }
        return HactarStatus.FRAMING;
    }

    // ──────────────────── Helpers ────────────────────

    private String buildReviewerSteerContent(HactarState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original goal\n")
                .append(state.getGoal() == null ? "" : state.getGoal())
                .append("\n\n## Plan sketch to review\n")
                .append(state.getPlanSketch() == null
                        ? "(empty)" : state.getPlanSketch())
                .append("\n\n## Your task\n")
                .append("Judge whether this plan is sound. Begin your reply "
                        + "with one of:\n")
                .append("    VERDICT: APPROVED\n")
                .append("    VERDICT: REJECTED\n")
                .append("After the verdict, list concrete concerns or "
                        + "improvements (numbered).\n");
        return sb.toString();
    }

    private void driveReviewerTurn(
            ThinkProcessDocument child, String parentId, String content) {
        SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                Instant.now(),
                /*idempotencyKey*/ null,
                "hactar:" + parentId,
                content);
        try {
            laneScheduler.submit(child.getId(),
                    () -> thinkEngineServiceProvider.getObject().steer(child, message))
                    .get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Reviewer interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new RuntimeException(
                    "Reviewer turn failed child='" + child.getId() + "': "
                            + cause.getMessage(), cause);
        }
    }

    private @Nullable String readLastAssistantText(
            String tenantId, String sessionId, String workerProcessId) {
        List<ChatMessageDocument> history = chatMessageService.history(
                tenantId, sessionId, workerProcessId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }

    private void cleanupReviewerChild(ThinkProcessDocument child) {
        try {
            thinkEngineServiceProvider.getObject().stop(child);
        } catch (RuntimeException e) {
            log.warn("Reviewer cleanup failed child='{}': {}",
                    child.getId(), e.toString());
        }
    }

    /**
     * Explicit param wins; otherwise falls back to the
     * {@code <parentRecipe>-reviewer} convention. The actual cascade
     * lookup happens in {@code recipeResolver.apply}.
     */
    static @Nullable String resolveReviewerRecipe(ThinkProcessDocument process) {
        String explicit = paramString(process, REVIEWER_RECIPE_KEY, null);
        if (explicit != null && !explicit.isBlank()) return explicit;
        String parent = process.getRecipeName();
        if (parent == null || parent.isBlank()) return null;
        return parent + "-reviewer";
    }

    private record ReviewerVerdict(String verdict) {}

    private static ReviewerVerdict parseVerdict(String reply) {
        for (String line : reply.split("\\R", 6)) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("approved")) return new ReviewerVerdict("APPROVED");
            if (lower.contains("rejected")) return new ReviewerVerdict("REJECTED");
        }
        return new ReviewerVerdict("REJECTED");
    }

    private static String abbreviateForReason(String s) {
        String trimmed = s.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 197) + "..." : trimmed;
    }
}
