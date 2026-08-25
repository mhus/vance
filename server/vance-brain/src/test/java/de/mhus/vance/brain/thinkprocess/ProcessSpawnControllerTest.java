package de.mhus.vance.brain.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starting a worker from a web client, and the two things that decide whether
 * it may happen.
 *
 * <p>{@code web: true} is the same flag the light-LLM route honours — one flag,
 * one meaning ("a web client may trigger this recipe"), with the *way* it is
 * triggered following from {@code internal}. These tests pin that the two routes
 * cannot be confused for one another: a config profile is refused here and
 * pointed at the other route.
 */
class ProcessSpawnControllerTest {

    private ProcessSpawnService spawnService;
    private RecipeLoader loader;
    private SessionService sessions;
    private ProcessSpawnController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        spawnService = mock(ProcessSpawnService.class);
        loader = mock(RecipeLoader.class);
        sessions = mock(SessionService.class);
        controller = new ProcessSpawnController(spawnService, loader, sessions,
                mock(RequestAuthority.class));
        request = mock(HttpServletRequest.class);

        SessionDocument s = new SessionDocument();
        s.setSessionId("sess_1");
        s.setProjectId("p");
        when(sessions.findBySessionId("sess_1")).thenReturn(Optional.of(s));
    }

    private static ResolvedRecipe recipe(boolean web, boolean internal) {
        return new ResolvedRecipe(
                "r", "test", "marvin", Map.of(),
                null, PromptMode.APPEND, null,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of(), List.of(), null, List.of(),
                false, internal, false, web, null, List.of(), List.of(), RecipeSource.RESOURCE);
    }

    private static ProcessSpawnController.SpawnRequestDto body(String recipe, String session) {
        ProcessSpawnController.SpawnRequestDto dto = new ProcessSpawnController.SpawnRequestDto();
        dto.setRecipe(recipe);
        dto.setSession(session);
        return dto;
    }

    private void released() {
        when(loader.load("acme", "p", "plan")).thenReturn(Optional.of(recipe(true, false)));
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId("pid");
        doc.setName("plan-1");
        doc.setThinkEngine("marvin");
        doc.setStatus(ThinkProcessStatus.IDLE);
        when(spawnService.spawn(any(), any())).thenReturn(doc);
    }

    @Test
    void spawn_startsAReleasedWorker_andReturnsTheRunHandle() {
        released();

        var out = controller.spawn("acme", "p", body("plan", "sess_1"), request);

        assertThat(out.processId()).isEqualTo("pid");
        // Prefixed: a bare id would not say which run source it belongs to.
        assertThat(out.runId()).isEqualTo("process:pid");
    }

    @Test
    void spawn_refusesARecipeWithoutTheWebRelease() {
        when(loader.load("acme", "p", "plan")).thenReturn(Optional.of(recipe(false, false)));

        assertThatThrownBy(() -> controller.spawn("acme", "p", body("plan", "sess_1"), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not released for web callers")
                .hasMessageContaining("`web: true`");
        verify(spawnService, never()).spawn(any(), any());
    }

    @Test
    void spawn_pointsAConfigProfileAtTheOtherRoute() {
        // Released, but `internal: true` — the caller most likely wanted a
        // single answer, not a worker. Refusing without saying where to go
        // would leave them guessing.
        when(loader.load("acme", "p", "plan")).thenReturn(Optional.of(recipe(true, true)));

        assertThatThrownBy(() -> controller.spawn("acme", "p", body("plan", "sess_1"), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("config profile")
                .hasMessageContaining("light-llm");
    }

    @Test
    void spawn_needsASessionAndSaysWhy() {
        assertThatThrownBy(() -> controller.spawn("acme", "p", body("plan", null), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("owned by a session");
    }

    @Test
    void spawn_refusesASessionFromAnotherProject() {
        // The path decides the project; following the session instead would let
        // a caller start work in a project they named nowhere.
        SessionDocument other = new SessionDocument();
        other.setSessionId("sess_2");
        other.setProjectId("elsewhere");
        when(sessions.findBySessionId("sess_2")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> controller.spawn("acme", "p", body("plan", "sess_2"), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("belongs to project 'elsewhere'");
    }

    @Test
    void spawn_unknownRecipeIs404_unknownSessionIs404() {
        when(loader.load("acme", "p", "ghost")).thenReturn(Optional.empty());
        when(sessions.findBySessionId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.spawn("acme", "p", body("ghost", "sess_1"), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> controller.spawn("acme", "p", body("plan", "nope"), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void spawn_generatesADistinctNameWhenNoneIsGiven() {
        // A name is unique within a session, so a fixed one would make the
        // second spawn of the same recipe collide.
        released();

        controller.spawn("acme", "p", body("plan", "sess_1"), request);

        verify(spawnService).spawn(org.mockito.ArgumentMatchers.argThat(
                r -> r.name().startsWith("plan-") && r.name().length() > "plan-".length()), any());
    }

    @Test
    void spawn_aFailedEngineStartIs502NotServerError() {
        // The process exists; the engine refused to start it. A caller that
        // retries blindly would collide on the name, so the status has to say
        // "upstream refused" rather than "we broke".
        released();
        when(spawnService.spawn(any(), any())).thenThrow(
                new ProcessSpawnService.StartFailedException("boom", new RuntimeException()));

        assertThatThrownBy(() -> controller.spawn("acme", "p", body("plan", "sess_1"), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
