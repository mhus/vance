package de.mhus.vance.foot.command;

import de.mhus.vance.api.recipe.RecipeListedResponse;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.NewSessionService;
import de.mhus.vance.foot.session.RecipePickerView;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /ui-new} — the Web-UI session-start dialog in the terminal: a
 * Lanterna recipe picker with a search field, the always-present
 * {@code Default} entry and the recipes grouped by category (order and
 * labels from {@code _vance/config/recipe_categories.yaml}, same endpoint
 * the Web-UI modal calls). Enter starts the picked recipe as a new session,
 * Esc cancels without side effects.
 *
 * <p>The recipe list is fetched <em>before</em> the fullscreen excursion so
 * a REST failure surfaces as a plain REPL error, and the bootstrap runs
 * <em>after</em> the excursion returns — progress output belongs in the
 * REPL, not painted over the picker.
 */
@Component
public class UiNewSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final BrainRestClientService rest;
    private final NewSessionService newSessions;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public UiNewSlashCommand(
            ConnectionService connection,
            BrainRestClientService rest,
            NewSessionService newSessions,
            ChatTerminal terminal,
            InterfaceService ui) {
        this.connection = connection;
        this.rest = rest;
        this.newSessions = newSessions;
        this.terminal = terminal;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "ui-new";
    }

    @Override
    public String description() {
        return "Pick a recipe in a fullscreen dialog and start a new session with it.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (!connection.isOpen()) {
            terminal.error("Not connected — /connect first.");
            return;
        }
        if (!ui.isFullscreenAvailable()) {
            terminal.error("No interactive terminal for the picker " + "(headless run?) — use /new [recipe] instead.");
            return;
        }
        String projectId = newSessions.resolveProject();
        if (projectId == null) {
            terminal.error("No project to start from — bind a session first "
                    + "(/session-create <projectId>) or set vance.bootstrap.project-id.");
            return;
        }

        RecipeListedResponse response;
        try {
            response = rest.listedRecipes(projectId);
        } catch (Exception e) {
            terminal.error("Could not load the recipe list: " + e.getMessage());
            return;
        }

        AtomicReference<RecipePickerView.Result> picked = new AtomicReference<>();
        ui.runFullscreen(session -> {
            picked.set(RecipePickerView.show(session.gui(), response));
        });

        RecipePickerView.Result result = picked.get();
        if (result == null || result.choice() == RecipePickerView.Choice.CANCEL) {
            terminal.info("New session cancelled.");
            return;
        }
        @Nullable String recipe = result.choice() == RecipePickerView.Choice.RECIPE ? result.recipeName() : null;
        try {
            newSessions.bootstrapNew(projectId, recipe);
        } catch (Exception e) {
            terminal.error("New session failed: " + e.getMessage());
        }
    }
}
