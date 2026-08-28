package de.mhus.vance.brain.ai.light;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The generic light-LLM route, and above all its second gate.
 *
 * <p>{@code internal: true} is the service's rule (config profile, not a
 * spawnable worker). {@code web: true} is this route's: may a browser trigger
 * this recipe at all. The flag sits on the recipe rather than on the caller
 * because a custom app *is* a web client — every web client reaches this route
 * with the same session, so a per-app permission would have been a fiction that
 * reads like a boundary.
 */
class LightLlmControllerTest {

    private LightLlmService service;
    private RecipeLoader loader;
    private LightLlmController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = mock(LightLlmService.class);
        loader = mock(RecipeLoader.class);
        controller = new LightLlmController(service, loader, mock(RequestAuthority.class));
        request = mock(HttpServletRequest.class);
    }

    /**
     * A real recipe, because {@link ResolvedRecipe} is a record and cannot be
     * mocked. Only `internal` and `web` matter here — the rest is the empty
     * shape the loader would produce for a bare config profile.
     */
    private static ResolvedRecipe recipe(boolean web) {
        return new ResolvedRecipe(
                "r", "test", "ford", Map.of(),
                null, PromptMode.APPEND, null,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of(), List.of(), null, List.of(),
                /*locked*/ false, /*internal*/ true, /*listed*/ false, /*web*/ web,
                null, List.of(), List.of(), /*tenants*/ List.of(), RecipeSource.RESOURCE);
    }

    private static LightLlmController.LightLlmCallRequestDto body(String recipe, String prompt) {
        LightLlmController.LightLlmCallRequestDto dto =
                new LightLlmController.LightLlmCallRequestDto();
        dto.setRecipe(recipe);
        dto.setPrompt(prompt);
        return dto;
    }

    @Test
    void call_runsAReleasedRecipe() throws Exception {
        when(loader.load("acme", "p", "summarise")).thenReturn(Optional.of(recipe(true)));
        when(service.call(any())).thenReturn("a summary");

        var out = controller.call("acme", "p", body("summarise", "text"), request);

        assertThat(out.recipe()).isEqualTo("summarise");
        assertThat(out.text()).isEqualTo("a summary");
    }

    @Test
    void call_refusesARecipeThatIsNotReleased_andNamesTheFix() {
        when(loader.load("acme", "p", "fook")).thenReturn(Optional.of(recipe(false)));

        assertThatThrownBy(() -> controller.call("acme", "p", body("fook", "text"), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not released for web callers")
                .hasMessageContaining("`web: true`");
    }

    @Test
    void call_doesNotReachTheModelWhenTheRecipeIsNotReleased() throws Exception {
        // The point of a gate: the refusal costs no tokens.
        when(loader.load("acme", "p", "fook")).thenReturn(Optional.of(recipe(false)));

        assertThatThrownBy(() -> controller.call("acme", "p", body("fook", "t"), request))
                .isInstanceOf(ResponseStatusException.class);

        verify(service, never()).call(any());
    }

    @Test
    void call_missingRecipeIs404_brokenOneIs422() {
        // Different answers because the fix is in a different place: a name the
        // caller got wrong, versus a document that does not parse.
        when(loader.load("acme", "p", "ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.call("acme", "p", body("ghost", "t"), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        when(loader.load("acme", "p", "broken")).thenThrow(new IllegalStateException("bad yaml"));
        assertThatThrownBy(() -> controller.call("acme", "p", body("broken", "t"), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void call_requiresARecipeAndAPrompt() {
        for (var dto : new LightLlmController.LightLlmCallRequestDto[] {
                body(null, "t"), body("  ", "t"), body("r", null), body("r", "   ") }) {
            assertThatThrownBy(() -> controller.call("acme", "p", dto, request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void call_passesTenantAndProjectIntoTheRequest() throws Exception {
        // Scope comes from the path, never from the body: a caller that could
        // name the project would read another project's configuration.
        when(loader.load("acme", "p", "summarise")).thenReturn(Optional.of(recipe(true)));
        when(service.call(any())).thenReturn("ok");

        controller.call("acme", "p", body("summarise", "text"), request);

        verify(service).call(org.mockito.ArgumentMatchers.argThat(r ->
                "acme".equals(r.getTenantId()) && "p".equals(r.getProjectId())
                        && "text".equals(r.getUserPrompt())));
    }
}
