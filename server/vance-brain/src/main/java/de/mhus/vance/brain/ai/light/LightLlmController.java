package de.mhus.vance.brain.ai.light;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * One single-shot LLM call, named by recipe — the generic counterpart to
 * purpose-built routes like {@code /follow-up}.
 *
 * <p>Vance is an AI tool, and until now nothing in a browser could ask a model
 * anything except through a route written for one specific question. This is the
 * route that takes the recipe name from the caller.
 *
 * <p><b>Two gates, and they answer different questions.</b>
 *
 * <ul>
 *   <li>{@code internal: true} — enforced by {@link LightLlmService}: is this a
 *       config profile rather than a spawnable worker? Without it, a caller
 *       could run a full worker recipe as a one-shot prompt.
 *   <li>{@code web: true} — enforced here: is this recipe meant to be triggered
 *       from a browser at all? Only the person who wrote the recipe knows, and
 *       the default is no.
 * </ul>
 *
 * <p><b>Why the flag is on the recipe and not on the caller.</b> The obvious
 * design was a per-app permission — but a custom app *is* a web client, and every
 * web client reaches this same route with the same session. A per-app flag would
 * have been a fiction that reads like a boundary. What can actually be decided is
 * which recipes are safe to expose, and that decision belongs in the document
 * where the model, the template and the retry budget already live.
 *
 * <p>Authorisation is {@code Project READ}, following {@code /follow-up}: the
 * caller is asking this project's configuration to answer something. Note what
 * that does and does not buy — it keeps strangers out, it does not meter
 * anybody. The cost control is the recipe: it fixes the model, the prompt
 * template and the attempt budget. There is deliberately no per-caller rate
 * limit here yet; if one is needed it belongs beside the other quota machinery,
 * not in this controller.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class LightLlmController {

    private final LightLlmService lightLlmService;
    private final RecipeLoader recipeLoader;
    private final RequestAuthority authority;

    @PostMapping("/brain/{tenant}/light-llm/{project}")
    public LightLlmCallResponseDto call(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @Valid @RequestBody LightLlmCallRequestDto body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        String recipeName = body.getRecipe() == null ? "" : body.getRecipe().trim();
        if (recipeName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'recipe' is required");
        }
        String prompt = body.getPrompt() == null ? "" : body.getPrompt();
        if (prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'prompt' is required");
        }

        ResolvedRecipe recipe;
        try {
            // A missing recipe is the caller's mistake, not a server fault — and
            // the name is the one thing worth repeating back. A *broken* one
            // throws, and that is a different answer (422 below), because the
            // fix is in the document rather than in the call.
            recipe = recipeLoader.load(tenant, project, recipeName)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No recipe '" + recipeName + "' in this project."));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Recipe '" + recipeName + "' could not be read: " + e.getMessage(), e);
        }
        if (!recipe.web()) {
            // Named as a property of the recipe, with the fix in it. A caller
            // reading "not allowed" would look for a permission to be granted;
            // what is actually missing is a line in a document.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Recipe '" + recipeName + "' is not released for web callers."
                            + " Add `web: true` to the recipe if it should be.");
        }

        try {
            String text = lightLlmService.call(LightLlmRequest.builder()
                    .recipeName(recipeName)
                    .userPrompt(prompt)
                    .pebbleVars(body.getVars())
                    .tenantId(tenant)
                    .projectId(project)
                    .build());
            return new LightLlmCallResponseDto(recipeName, text);
        } catch (LightLlmException e) {
            // Includes the `internal: true` refusal from the service — a second
            // gate with its own message, which is why it is not pre-checked
            // here: one owner per rule.
            log.debug("light-llm call failed tenant='{}' project='{}' recipe='{}': {}",
                    tenant, project, recipeName, e.toString());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        }
    }

    /** Request body of {@link #call}. */
    public static class LightLlmCallRequestDto {
        private @jakarta.annotation.Nullable String recipe;
        private @jakarta.annotation.Nullable String prompt;
        private @jakarta.annotation.Nullable Map<String, Object> vars;

        public @jakarta.annotation.Nullable String getRecipe() { return recipe; }
        public void setRecipe(@jakarta.annotation.Nullable String recipe) { this.recipe = recipe; }
        public @jakarta.annotation.Nullable String getPrompt() { return prompt; }
        public void setPrompt(@jakarta.annotation.Nullable String prompt) { this.prompt = prompt; }
        public @jakarta.annotation.Nullable Map<String, Object> getVars() { return vars; }
        public void setVars(@jakarta.annotation.Nullable Map<String, Object> vars) {
            this.vars = vars;
        }
    }

    /** The reply text, verbatim, plus the recipe that produced it. */
    public record LightLlmCallResponseDto(String recipe, String text) {}
}
