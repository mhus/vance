package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.recipe.RecipeCategoryDto;
import de.mhus.vance.api.recipe.RecipeListedDto;
import de.mhus.vance.api.recipe.RecipeListedResponse;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Cascade-aware grouping order and labels for the user-facing recipe
 * picker.
 *
 * <p>Reads {@code _vance/config/recipe_categories.yaml} through
 * {@link DocumentService#lookupCascade} — project → {@code _vance} →
 * bundled classpath default — the same tiering every recipe resolves
 * through. The document is a <b>sort help, not a registry</b>:
 *
 * <ul>
 * <li>it is neither complete (categories not named there still appear,
 * appended after the documented ones, alphabetical) nor mandatory (no
 * document means plain alphabetical category order and no labels);</li>
 * <li>a malformed document is logged and ignored — a broken config file
 * must not stop a user from starting a session (fail-open, same
 * philosophy as report themes).</li>
 * </ul>
 *
 * <p>Entries carry an optional {@code title} map (language code → text).
 * The server never resolves a locale; clients pick by their UI language
 * and fall back to a humanised category id. See
 * {@code specification/public/recipes.md} §6e.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeCategoriesService {

    /** Cascade path of the category document, resolved relative to every tier. */
    public static final String CATEGORY_CONFIG_PATH = "_vance/config/recipe_categories.yaml";

    private final DocumentService documentService;

    /**
     * Builds the picker response: the category metadata in document order
     * plus the recipes sorted for grouped rendering.
     *
     * <p>Sort: documented categories first (document order), undocumented
     * categories next (alphabetical, so a tenant recipe with a new
     * {@code category:} lands predictably without editing the document),
     * entries without a category last. Within a group the existing
     * title-or-name, case-insensitive order applies.
     *
     * <p>The sort only orders — it never drops an entry. Grouping by key
     * (first occurrence wins) is a pure client concern and works on this
     * order without a second round trip.
     */
    public RecipeListedResponse arrange(String tenantId, @Nullable String projectId, List<RecipeListedDto> recipes) {
        List<RecipeCategoryDto> categories = categories(tenantId, projectId);
        Map<String, Integer> ranks = groupRanks(categories, recipes);
        List<RecipeListedDto> sorted = new ArrayList<>(recipes);
        sorted.sort(Comparator.comparingInt((RecipeListedDto dto) -> rankOf(dto, ranks))
                .thenComparing(RecipeCategoriesService::displayName, String.CASE_INSENSITIVE_ORDER));
        return RecipeListedResponse.builder()
                .categories(categories)
                .recipes(sorted)
                .build();
    }

    /**
     * The category metadata in document order. Empty when no tier carries
     * the document or when it is malformed — the response then simply has
     * no labels and clients fall back to humanising the recipe's own
     * {@code category} value.
     */
    private List<RecipeCategoryDto> categories(String tenantId, @Nullable String projectId) {
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, RecipeLoader.effectiveProjectId(projectId), CATEGORY_CONFIG_PATH);
        if (hit.isEmpty()) {
            return List.of();
        }
        LookupResult result = hit.get();
        try {
            return parse(result.content());
        } catch (RuntimeException e) {
            log.warn(
                    "RecipeCategoriesService: ignoring malformed category document at '{}' (source {}): {}",
                    result.path(),
                    result.source(),
                    e.getMessage());
            return List.of();
        }
    }

    // ──────────────────── document format ────────────────────

    /**
     * Parses the category document:
     *
     * <pre>{@code
     * categories:
     *   - id: coding
     *     title:
     *       en: Coding
     *       de: Programmierung
     * }</pre>
     *
     * <p>Ids are normalised the same way the recipe field is (trim,
     * lower-case) so hand-written YAML on both sides matches without
     * per-call work. A duplicate id is malformed — an order in which a
     * key appears twice does not define an order.
     */
    private static List<RecipeCategoryDto> parse(String content) {
        Object parsed = new Yaml().load(content);
        if (parsed == null) {
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("category document must have a top-level map");
        }
        Object rawCategories = rawMap.get("categories");
        if (rawCategories == null) {
            return List.of();
        }
        if (!(rawCategories instanceof List<?> list)) {
            throw new IllegalStateException("'categories' must be a list");
        }
        List<RecipeCategoryDto> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                throw new IllegalStateException("categories[" + i + "] must be a map with an 'id'");
            }
            Object rawId = entry.get("id");
            if (!(rawId instanceof String id) || id.isBlank()) {
                throw new IllegalStateException("categories[" + i + "] needs a non-blank string 'id'");
            }
            String normalizedId = id.trim().toLowerCase(Locale.ROOT);
            Map<String, String> title = parseTitle(entry.get("title"), i);
            if (out.stream().anyMatch(c -> c.getId().equals(normalizedId))) {
                throw new IllegalStateException("duplicate category id '" + normalizedId + "'");
            }
            out.add(RecipeCategoryDto.builder().id(normalizedId).title(title).build());
        }
        return List.copyOf(out);
    }

    /**
     * Parses the optional {@code title} map. Blank language codes or texts
     * are malformed, not noise to drop silently — the document is small,
     * hand-written, and an author wants to hear about a typo; the
     * fail-open wrapper above turns it into a warning plus plain ordering.
     */
    private static Map<String, String> parseTitle(@Nullable Object raw, int idx) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> rawTitle)) {
            throw new IllegalStateException("categories[" + idx + "].title must be a map of language code → text");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawTitle.entrySet()) {
            if (!(e.getKey() instanceof String lang)
                    || lang.isBlank()
                    || !(e.getValue() instanceof String text)
                    || text.isBlank()) {
                throw new IllegalStateException(
                        "categories[" + idx + "].title entries must be non-blank language code → text");
            }
            out.put(lang.trim(), text.trim());
        }
        return out;
    }

    // ──────────────────── ordering ────────────────────

    /**
     * Rank per category id: documented categories in document order,
     * then undocumented ones alphabetically. Recipes without a category
     * are not in the map — {@link #rankOf} sends them to the end.
     */
    private static Map<String, Integer> groupRanks(List<RecipeCategoryDto> categories, List<RecipeListedDto> recipes) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int next = 0;
        for (RecipeCategoryDto category : categories) {
            ranks.putIfAbsent(category.getId(), next++);
        }
        List<String> undocumented = recipes.stream()
                .map(RecipeListedDto::getCategory)
                .filter(Objects::nonNull)
                .filter(category -> !ranks.containsKey(category))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        for (String category : undocumented) {
            ranks.put(category, next++);
        }
        return ranks;
    }

    private static int rankOf(RecipeListedDto dto, Map<String, Integer> ranks) {
        if (dto.getCategory() == null) {
            return Integer.MAX_VALUE;
        }
        return ranks.getOrDefault(dto.getCategory(), Integer.MAX_VALUE);
    }

    /** Picker display label: recipe title with the recipe name as fallback. */
    private static String displayName(RecipeListedDto dto) {
        return dto.getTitle() != null ? dto.getTitle() : dto.getName();
    }
}
