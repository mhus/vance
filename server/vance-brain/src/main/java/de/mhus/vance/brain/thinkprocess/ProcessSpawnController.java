package de.mhus.vance.brain.thinkprocess;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Start a worker from a web client — the piece that was missing between
 * "an app can watch a run" and "an app can cause one".
 *
 * <p>{@code POST /brain/{tenant}/processes/{project}} with
 * {@code {recipe, session, name?, title?, goal?, params?}}. What comes back is
 * the process id and its status; what happens next is visible through
 * {@code /runs}, which already lists plan-shaped processes and offers
 * pause/resume/stop where the run allows them.
 *
 * <p><b>The same release flag as the light-LLM route, and deliberately not a
 * second one.</b> {@code web: true} on the recipe means "a web client may
 * trigger this". Which *way* it is triggered follows from {@code internal}: a
 * config profile ({@code internal: true}) is a one-shot call and belongs to
 * {@code /light-llm}; a worker recipe ({@code internal: false}) is spawnable and
 * belongs here. The two cases cannot collide, because each route rejects the
 * other's kind — so one flag with one meaning is enough, and a second flag would
 * have been a thing to keep in sync.
 *
 * <p><b>A session is required, and that is not bureaucracy.</b> A think process
 * is owned by a session — that is where its chat log lives, where a suspend
 * cascades from, and what a reader opens to see what the worker said. A process
 * without one would be work nobody can look at.
 *
 * <p>Authorisation is {@code Project WRITE}: this starts work and spends tokens,
 * which is a change, not a read. Note what the flag does and does not buy — it
 * decides *which* recipes are startable, not how often. There is no per-caller
 * limit here; that belongs beside the quota machinery.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ProcessSpawnController {

    private final ProcessSpawnService spawnService;
    private final RecipeLoader recipeLoader;
    private final SessionService sessionService;
    private final RequestAuthority authority;

    @PostMapping("/brain/{tenant}/processes/{project}")
    public SpawnResponseDto spawn(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @Valid @RequestBody SpawnRequestDto body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);

        String recipeName = trimmed(body.getRecipe());
        if (recipeName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'recipe' is required");
        }
        String sessionId = trimmed(body.getSession());
        if (sessionId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'session' is required — a think process is owned by a session,"
                            + " and one without it is work nobody can look at.");
        }

        SessionDocument session = sessionService.findBySessionId(sessionId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No session '" + sessionId + "'."));
        if (!project.equals(session.getProjectId())) {
            // The path decides the project, so a session from elsewhere is a
            // mistake worth naming rather than a scope to silently follow.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Session '" + sessionId + "' belongs to project '"
                            + session.getProjectId() + "', not '" + project + "'.");
        }

        ResolvedRecipe recipe = loadRecipe(tenant, project, recipeName);
        if (!recipe.web()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Recipe '" + recipeName + "' is not released for web callers."
                            + " Add `web: true` to the recipe if it should be.");
        }
        if (recipe.internal()) {
            // Pointed at the other route rather than just refused: an
            // `internal: true` recipe is a config profile, and the caller most
            // likely wanted a single answer rather than a worker.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Recipe '" + recipeName + "' is `internal: true` — a config profile, not a"
                            + " spawnable worker. Use POST /brain/{tenant}/light-llm/{project}"
                            + " for a single-shot call.");
        }

        ProcessSpawnService.SpawnRequest req = ProcessSpawnService.SpawnRequest.builder()
                .tenantId(tenant)
                .projectId(project)
                .sessionId(sessionId)
                .name(nameFor(body, recipeName))
                .recipe(recipeName)
                .profile("web")
                .title(body.getTitle())
                .goal(body.getGoal())
                .params(body.getParams())
                .build();
        try {
            AppliedRecipe applied = spawnService.resolve(req);
            ThinkProcessDocument created = spawnService.spawn(req, applied);
            return new SpawnResponseDto(created.getId(), created.getName(),
                    created.getThinkEngine(), created.getStatus(),
                    "process:" + created.getId());
        } catch (ProcessSpawnService.UnknownTargetException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (ProcessSpawnService.AlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (ProcessSpawnService.StartFailedException e) {
            // 502 rather than 500: the process exists, the engine refused to
            // start it. A caller that retries blindly would collide on the name.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    private ResolvedRecipe loadRecipe(String tenant, String project, String name) {
        try {
            return recipeLoader.load(tenant, project, name).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No recipe '" + name + "' in this project."));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Recipe '" + name + "' could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * The process name, generated when the caller did not pick one.
     *
     * <p>A name is unique within a session, so two spawns of the same recipe
     * would collide on a fixed one. The suffix is the clock rather than a
     * counter: a counter needs a read of what is already there, and two callers
     * would read the same number.
     */
    private static String nameFor(SpawnRequestDto body, String recipeName) {
        String given = trimmed(body.getName());
        if (!given.isEmpty()) return given;
        return recipeName + "-" + Long.toString(System.currentTimeMillis(), 36);
    }

    private static String trimmed(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    /** Request body of {@link #spawn}. */
    public static class SpawnRequestDto {
        private @Nullable String recipe;
        private @Nullable String session;
        private @Nullable String name;
        private @Nullable String title;
        private @Nullable String goal;
        private @Nullable Map<String, Object> params;

        public @Nullable String getRecipe() { return recipe; }
        public void setRecipe(@Nullable String recipe) { this.recipe = recipe; }
        public @Nullable String getSession() { return session; }
        public void setSession(@Nullable String session) { this.session = session; }
        public @Nullable String getName() { return name; }
        public void setName(@Nullable String name) { this.name = name; }
        public @Nullable String getTitle() { return title; }
        public void setTitle(@Nullable String title) { this.title = title; }
        public @Nullable String getGoal() { return goal; }
        public void setGoal(@Nullable String goal) { this.goal = goal; }
        public @Nullable Map<String, Object> getParams() { return params; }
        public void setParams(@Nullable Map<String, Object> params) { this.params = params; }
    }

    /**
     * What was started. {@code runId} is the {@code /runs} handle — prefixed,
     * because a bare id would not say which source it belongs to.
     */
    public record SpawnResponseDto(
            String processId, String name, String engine,
            ThinkProcessStatus status, String runId) {}
}
