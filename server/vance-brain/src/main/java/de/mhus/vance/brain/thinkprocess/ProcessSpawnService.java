package de.mhus.vance.brain.thinkprocess;

import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolve a recipe, create the think process, start its engine on the process
 * lane — the sequence every spawn has to get right, in one place.
 *
 * <p>Extracted when a second caller appeared (the REST spawn route). It was
 * about seventy lines inside the WebSocket handler, and the part that matters is
 * not the length: the start runs **on the process lane**, because an off-lane
 * start races a concurrent turn or steer for the same process on a multi-client
 * session, with both mutating the document and the chat log. A second copy of
 * that would be a second chance to get it wrong, and the copy that drifts is
 * always the one nobody is looking at.
 *
 * <p>Transport-neutral by construction: it throws, and each caller renders the
 * failure in its own vocabulary — WebSocket error frames on one side, HTTP
 * statuses on the other. That is the only reason this is a service and not a
 * shared base class.
 *
 * <p>It deliberately does **not** decide who may spawn. Authorisation differs
 * per surface — the WebSocket path enforces {@code Session START} for a bound
 * session, the REST path enforces {@code Project WRITE} plus the recipe's own
 * web release — and folding both into here would mean a call site could forget
 * to pass its context and still get a process.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessSpawnService {

    private final RecipeResolver recipeResolver;
    private final ThinkEngineService thinkEngineService;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;

    /** Everything the spawn needs that is not policy. */
    @Builder
    public record SpawnRequest(
            String tenantId,
            @Nullable String projectId,
            String sessionId,
            String name,
            @Nullable String recipe,
            @Nullable String profile,
            @Nullable String title,
            @Nullable String goal,
            @Nullable Map<String, Object> params) {}

    /** Raised when the recipe or the engine it names does not exist. */
    public static class UnknownTargetException extends RuntimeException {
        public UnknownTargetException(String message) {
            super(message);
        }
    }

    /** Raised when a process of that name already exists in the session. */
    public static class AlreadyExistsException extends RuntimeException {
        public AlreadyExistsException(String message) {
            super(message);
        }
    }

    /** Raised when the engine's {@code start()} threw. The process exists. */
    public static class StartFailedException extends RuntimeException {
        public StartFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The resolved recipe for a request, without creating anything.
     *
     * <p>Separate because the REST path has to look at the recipe *before* it
     * spawns — its web release is a property of the recipe — and resolving twice
     * would let the answer change between the check and the act.
     */
    public AppliedRecipe resolve(SpawnRequest req) {
        try {
            return recipeResolver.applyDefaulting(
                    req.tenantId(), req.projectId(), req.recipe(), req.profile(), req.params());
        } catch (RecipeResolver.UnknownRecipeException | RecipeResolver.UnknownEngineException e) {
            throw new UnknownTargetException(e.getMessage());
        }
    }

    /** Create and start, from an already-resolved recipe. */
    public ThinkProcessDocument spawn(SpawnRequest req, AppliedRecipe applied) {
        ThinkEngine engine = thinkEngineService.resolve(applied.engine())
                .orElseThrow(() -> new UnknownTargetException(
                        "Recipe '" + applied.name() + "' references unknown engine '"
                                + applied.engine() + "'"));

        ThinkProcessDocument created;
        try {
            created = thinkProcessService.create(
                    req.tenantId(), req.projectId(), req.sessionId(), req.name(),
                    engine.name(), engine.version(),
                    req.title(), req.goal(),
                    /*parentProcessId*/ null,
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null ? null : Set.copyOf(applied.allowedSkills()));
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException e) {
            throw new AlreadyExistsException(e.getMessage());
        }

        // ON the process lane, like every other spawn site
        // (SessionChatBootstrapper / AgrajagSpawnerService / Trillian): an
        // off-lane start would race a concurrent runTurn/steer for the same
        // process on a multi-client session, both mutating the doc and the chat
        // log. Lane serialisation is an invariant, not an optimisation.
        Throwable failure = null;
        try {
            laneScheduler.submit(created.getId(), () -> {
                thinkEngineService.start(created);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failure = ie;
        } catch (ExecutionException ee) {
            failure = ee.getCause() == null ? ee : ee.getCause();
        }
        if (failure != null) {
            log.error("Engine start failed for process id='{}' engine='{}'",
                    created.getId(), created.getThinkEngine(), failure);
            throw new StartFailedException(
                    "Engine start failed: " + failure.getMessage(), failure);
        }

        // Re-read: start() moves the status, and a caller rendering from the
        // pre-start document would report INIT for a process that is running.
        return thinkProcessService.findById(created.getId()).orElse(created);
    }

    /** Resolve and spawn in one step, for callers with no policy in between. */
    public ThinkProcessDocument spawn(SpawnRequest req) {
        return spawn(req, resolve(req));
    }
}
