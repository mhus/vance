package de.mhus.vance.foot.session;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import de.mhus.vance.api.recipe.RecipeCategoryDto;
import de.mhus.vance.api.recipe.RecipeListedDto;
import de.mhus.vance.api.recipe.RecipeListedResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Lanterna recipe picker for {@code /ui-new} — the terminal twin of the
 * Web-UI session-start modal. Same response, same layout: a search field
 * on top, the always-present {@code Default} entry first, then the recipes
 * in the server's group order with a header row per category, {@code Cancel}
 * last. Enter starts the session with the picked recipe, Esc aborts.
 *
 * <p>Category labels resolve from the document's title map via English
 * ({@code en}) with a humanised id as fallback — foot has no UI-locale
 * concept, and the map stays open for future language configuration.
 *
 * <p>Holds no state of its own beyond the window; the row model is built by
 * the pure {@link #buildRows(List, List, String)} so grouping, filtering and
 * label fallback are testable without a TextGUI.
 */
public final class RecipePickerView {

    private RecipePickerView() {}

    /** What the user picked. */
    public enum Choice {
        /** Start with the project default recipe ({@code chatRecipe: null}). */
        DEFAULT,
        /** Start with the named recipe. */
        RECIPE,
        /** Esc or the trailing cancel row. */
        CANCEL
    }

    /** Result of the picker: the {@link Choice} plus the recipe name (only for RECIPE). */
    public record Result(Choice choice, @Nullable String recipeName) {

        static Result of(Choice choice) {
            return new Result(choice, null);
        }
    }

    /** One rendered row of the list. Headers and cancel carry no recipe name. */
    public record Row(Kind kind, String text, @Nullable String recipeName) {}

    public enum Kind {
        DEFAULT,
        HEADER,
        RECIPE,
        CANCEL
    }

    /**
     * Opens the picker and blocks until the user selects a row or cancels.
     * Never returns {@code null} — closing the window without a pick (Esc)
     * maps to {@link Choice#CANCEL}, unlike the session pickers where
     * {@code null} already means that.
     */
    public static Result show(WindowBasedTextGUI gui, RecipeListedResponse response) {
        Result[] picked = new Result[] {Result.of(Choice.CANCEL)};

        BasicWindow window = new BasicWindow("New session — pick a recipe");
        window.setHints(Set.of(Window.Hint.CENTERED, Window.Hint.FIT_TERMINAL_WINDOW));
        window.setCloseWindowWithEscape(true);

        ActionListBox listBox = new ActionListBox();
        TextBox filter = new TextBox();
        filter.setTextChangeListener(
                (newText, changedByUser) -> rebuildList(listBox, response, picked, window, newText));

        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        Label header = new Label(countLine(response.getRecipes()));
        header.addStyle(SGR.BOLD);
        panel.addComponent(header);
        panel.addComponent(new Label("Filter:"));
        panel.addComponent(filter);
        panel.addComponent(listBox.withBorder(Borders.singleLine("Recipes")));
        Label hint = new Label("[Enter] start   [Tab] filter ↔ list   [Esc] cancel");
        hint.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        panel.addComponent(hint);

        window.setComponent(panel);
        // Focus starts on the list — picking is the common path, the filter
        // is one Tab away.
        window.setFocusedInteractable(listBox);

        // Esc isn't tied to a specific component — let it be the cancel key.
        window.addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
            @Override
            public void onInput(
                    Window basePane, KeyStroke keyStroke, java.util.concurrent.atomic.AtomicBoolean deliverEvent) {
                if (keyStroke.getKeyType() == KeyType.Escape) {
                    picked[0] = Result.of(Choice.CANCEL);
                    window.close();
                    deliverEvent.set(false);
                }
            }
        });

        rebuildList(listBox, response, picked, window, "");
        gui.addWindowAndWait(window);
        return picked[0];
    }

    private static void rebuildList(
            ActionListBox listBox, RecipeListedResponse response, Result[] picked, BasicWindow window, String filter) {
        listBox.clearItems();
        List<Row> rows = buildRows(
                response.getRecipes() == null ? List.of() : response.getRecipes(),
                response.getCategories() == null ? List.of() : response.getCategories(),
                filter);
        for (Row row : rows) {
            switch (row.kind()) {
                case HEADER ->
                    listBox.addItem(row.text(), () -> {
                        /* header — no action */
                    });
                case DEFAULT ->
                    listBox.addItem(row.text(), () -> {
                        picked[0] = Result.of(Choice.DEFAULT);
                        window.close();
                    });
                case RECIPE ->
                    listBox.addItem(row.text(), () -> {
                        picked[0] = new Result(Choice.RECIPE, row.recipeName());
                        window.close();
                    });
                case CANCEL ->
                    listBox.addItem(row.text(), () -> {
                        picked[0] = Result.of(Choice.CANCEL);
                        window.close();
                    });
            }
        }
    }

    // ──────────────────── row model (pure, tested) ────────────────────

    /**
     * Builds the row model: {@code Default} first, then the recipes in the
     * server's group order with a header row whenever the category changes
     * (a category change is a new group — the server sorts groups
     * contiguously), {@code Cancel} last.
     *
     * <p>Filter: case-insensitive substring over display name and
     * description, applied before grouping so groups without matches
     * vanish. {@code Default} and {@code Cancel} are not filtered.
     */
    static List<Row> buildRows(
            List<RecipeListedDto> recipes, List<RecipeCategoryDto> categories, @Nullable String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        Map<String, String> labels = categoryLabels(categories);

        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Kind.DEFAULT, "Default — use the project default recipe", null));
        String lastCategory = "\u0000sentinel-never-matches";
        for (RecipeListedDto recipe : recipes) {
            if (!needle.isEmpty() && !matches(recipe, needle)) {
                continue;
            }
            String category = recipe.getCategory();
            if (category != null && !category.equals(lastCategory)) {
                rows.add(new Row(Kind.HEADER, "── " + labels.getOrDefault(category, humanize(category)) + " ──", null));
            }
            if (category != null) {
                lastCategory = category;
            }
            rows.add(new Row(Kind.RECIPE, formatRow(recipe), recipe.getName()));
        }
        rows.add(new Row(Kind.CANCEL, "── Cancel ──", null));
        return List.copyOf(rows);
    }

    /** id → display label: {@code title.en}, else a humanised id. */
    private static Map<String, String> categoryLabels(List<RecipeCategoryDto> categories) {
        Map<String, String> labels = new HashMap<>();
        for (RecipeCategoryDto category : categories) {
            String label =
                    category.getTitle() == null ? null : category.getTitle().get("en");
            labels.put(category.getId(), label != null && !label.isBlank() ? label : humanize(category.getId()));
        }
        return labels;
    }

    private static boolean matches(RecipeListedDto recipe, String needle) {
        String title = recipe.getTitle() != null && !recipe.getTitle().isBlank() ? recipe.getTitle() : recipe.getName();
        if (title.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String description = recipe.getDescription();
        return description != null && description.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String formatRow(RecipeListedDto recipe) {
        String title = recipe.getTitle() != null && !recipe.getTitle().isBlank() ? recipe.getTitle() : recipe.getName();
        StringBuilder row = new StringBuilder(title);
        if (recipe.getTitle() != null
                && !recipe.getTitle().isBlank()
                && !recipe.getTitle().equals(recipe.getName())) {
            row.append("  [").append(recipe.getName()).append(']');
        }
        String description = firstLine(recipe.getDescription());
        if (!description.isEmpty()) {
            row.append(" — ").append(truncate(description, 60));
        }
        return row.toString();
    }

    /** First non-blank line of the multi-line description — the picker is one line per recipe. */
    private static String firstLine(@Nullable String description) {
        if (description == null) return "";
        for (String line : description.split("\n")) {
            if (!line.isBlank()) return line.trim();
        }
        return "";
    }

    /** {@code code-read} → {@code Code Read}. */
    private static String humanize(String id) {
        StringBuilder out = new StringBuilder(id.length());
        boolean upper = true;
        for (char c : id.toCharArray()) {
            if (c == '-') {
                out.append(' ');
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String countLine(@Nullable List<RecipeListedDto> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return "No listed recipes — Default starts a plain session.";
        }
        return recipes.size() + " recipe" + (recipes.size() == 1 ? "" : "s") + " — Enter starts, Esc cancels.";
    }
}
