package de.mhus.vance.brain.addon;

import de.mhus.vance.api.addon.AddonMenuItemDto;
import de.mhus.vance.api.addon.AddonProfileTabDto;
import de.mhus.vance.api.addon.AddonTileDto;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads every addon's {@code META-INF/vance-addon.yaml} from the classpath once
 * at startup and exposes the declarative blocks the Web-UI needs before it has
 * loaded any federation remote: {@code tile:}, {@code profile:}, {@code menu:}
 * and {@code kinds:}. The addon manifest is the single source for all four —
 * the same file the dev-server middleware reads — so {@code GET /face/addons}
 * carries them in production just as the dev stand-in does.
 *
 * <p>{@code kinds:} lists the document-kind ids an addon's {@code ./register}
 * expose contributes. It is a <b>load trigger, not a copy of the matching
 * rule</b>: the host uses it to decide <i>which remote to fetch</i> when a
 * document of that kind is opened, and the addon's own {@code matches()}
 * predicate still decides whether the entry applies. Declaring too much
 * therefore costs a round trip; declaring too little makes the kind
 * unreachable.
 */
@Component
@Slf4j
public class AddonManifestRegistry {

    /** The menus a contributed entry may land in. Anything else is dropped. */
    private static final java.util.Set<String> MENU_SLOTS = java.util.Set.of("view", "actions", "extras");

    private final Map<String, AddonTileDto> tilesById;
    private final Map<String, AddonProfileTabDto> profileTabsById;
    private final Map<String, List<AddonMenuItemDto>> menusById;
    private final Map<String, List<String>> kindsById;
    private final java.util.Set<String> eagerIds;

    public AddonManifestRegistry(ResourcePatternResolver resourceResolver) {
        Map<String, AddonTileDto> map = new HashMap<>();
        Map<String, AddonProfileTabDto> profiles = new HashMap<>();
        Map<String, List<AddonMenuItemDto>> menus = new HashMap<>();
        Map<String, List<String>> kinds = new HashMap<>();
        java.util.Set<String> eager = new java.util.HashSet<>();
        try {
            Resource[] manifests = resourceResolver.getResources("classpath*:META-INF/vance-addon.yaml");
            Yaml yaml = new Yaml();
            for (Resource manifest : manifests) {
                try (InputStream in = manifest.getInputStream()) {
                    Object parsed = yaml.load(in);
                    if (!(parsed instanceof Map<?, ?> m)) {
                        continue;
                    }
                    if (!(m.get("id") instanceof String id) || id.isBlank()) {
                        continue;
                    }
                    // A profile tab and a landing tile are independent: an
                    // addon may contribute one, both or neither.
                    if (m.get("profile") instanceof Map<?, ?> profile) {
                        String tabLabel = str(profile.get("label"));
                        if (tabLabel != null && !tabLabel.isBlank()) {
                            profiles.put(id, AddonProfileTabDto.builder()
                                    .label(tabLabel)
                                    .expose(str(profile.get("expose")))
                                    .sortIndex(intOrNull(profile.get("sortIndex")))
                                    .minLevel(str(profile.get("minLevel")))
                                    .build());
                        }
                    }
                    // Cortex menu entries. Same placement rule as `kinds:`
                    // below — before the tile block, which ends in a
                    // `continue`. Only stored when the addon actually
                    // contributes one, so the field stays off the wire for
                    // the addons that do not.
                    List<AddonMenuItemDto> menuItems = menuItems(id, m.get("menu"));
                    if (!menuItems.isEmpty()) {
                        menus.put(id, menuItems);
                    }
                    // Parsed BEFORE the tile block, which ends in a `continue`:
                    // most addons declare kinds and no tile, so reading kinds
                    // afterwards would silently skip nearly all of them.
                    //
                    // Keyed on PRESENCE, not on emptiness. An absent `kinds:`
                    // means "did not say" and makes the host fall back to
                    // loading the remote eagerly, so an addon cannot break by
                    // forgetting to declare. An explicit empty list means
                    // "contributes none" and is the way to opt out of that
                    // fallback. Collapsing the two would remove the only way to
                    // say the second thing.
                    if (m.containsKey("kinds")) {
                        kinds.put(id, kindIds(m.get("kinds")));
                    }
                    if (Boolean.TRUE.equals(m.get("eager"))) {
                        eager.add(id);
                    }
                    if (!(m.get("tile") instanceof Map<?, ?> tile)) {
                        continue;
                    }
                    String label = str(tile.get("label"));
                    if (label == null || label.isBlank()) {
                        continue; // a tile without a label is not renderable
                    }
                    map.put(id, AddonTileDto.builder()
                            .label(label)
                            .description(str(tile.get("description")))
                            .minLevel(str(tile.get("minLevel")))
                            .build());
                } catch (RuntimeException | java.io.IOException e) {
                    log.warn("AddonManifestRegistry: cannot read {}: {}", manifest, e.toString());
                }
            }
        } catch (java.io.IOException e) {
            log.warn("AddonManifestRegistry: classpath scan failed: {}", e.toString());
        }
        this.tilesById = Map.copyOf(map);
        this.profileTabsById = Map.copyOf(profiles);
        this.menusById = Map.copyOf(menus);
        this.kindsById = Map.copyOf(kinds);
        this.eagerIds = java.util.Set.copyOf(eager);
        log.info("AddonManifestRegistry: {} addon tile(s), {} profile tab(s), {} menu-contributing addon(s), "
                        + "{} kind-contributing addon(s), {} eager addon(s) discovered",
                tilesById.size(), profileTabsById.size(), menusById.size(), kindsById.size(),
                eagerIds.size());
    }

    /** The declarative tile for {@code addonId}, or {@code null} when it declares none. */
    public @Nullable AddonTileDto tileFor(String addonId) {
        return tilesById.get(addonId);
    }

    /** The profile tab {@code addonId} declares, or {@code null} for none. */
    public @Nullable AddonProfileTabDto profileTabFor(String addonId) {
        return profileTabsById.get(addonId);
    }

    /**
     * The document-kind ids {@code addonId} declares, or {@code null} when its
     * manifest carries no {@code kinds:} key at all.
     *
     * <p>The distinction is load-bearing and survives to the wire
     * ({@code JsonInclude.NON_NULL} keeps null off it): <b>null means "did not
     * say"</b> and the host loads the remote eagerly, <b>empty means
     * "contributes none"</b> and the host skips it.
     */
    public @Nullable List<String> kindsFor(String addonId) {
        return kindsById.get(addonId);
    }

    /**
     * Whether {@code addonId} asked to be loaded at boot rather than when one
     * of its kinds is opened. Null rather than {@code false} for the normal
     * case, so the field stays off the wire ({@code JsonInclude.NON_NULL}).
     */
    public @Nullable Boolean eagerFor(String addonId) {
        return eagerIds.contains(addonId) ? Boolean.TRUE : null;
    }

    /**
     * The Cortex menu entries {@code addonId} declares, or {@code null} when
     * it declares none.
     */
    public @Nullable List<AddonMenuItemDto> menuFor(String addonId) {
        return menusById.get(addonId);
    }

    /**
     * Menu entries from a {@code menu:} node.
     *
     * <p>Malformed entries are dropped with a warning rather than rejected —
     * one bad line must not cost the deployment its boot. Three ways to be
     * dropped, all of them "not renderable": no id, no label, or a slot that
     * names no menu. The last one is deliberately not defaulted: an entry
     * silently moved to {@code extras} is an entry the author cannot find
     * where they put it.
     */
    private List<AddonMenuItemDto> menuItems(String addonId, @Nullable Object node) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof Iterable<?> entries)) {
            log.warn("AddonManifestRegistry: addon '{}' has a 'menu:' that is not a list — ignored", addonId);
            return List.of();
        }
        List<AddonMenuItemDto> items = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> e)) {
                continue;
            }
            String id = trimmed(e.get("id"));
            String label = trimmed(e.get("label"));
            String slot = trimmed(e.get("slot"));
            if (id == null || label == null) {
                log.warn("AddonManifestRegistry: addon '{}' declares a menu entry without id or label — dropped",
                        addonId);
                continue;
            }
            if (slot == null || !MENU_SLOTS.contains(slot.toLowerCase(java.util.Locale.ROOT))) {
                log.warn("AddonManifestRegistry: addon '{}' menu entry '{}' names slot '{}' — expected one of {};"
                                + " entry dropped",
                        addonId, id, slot, MENU_SLOTS);
                continue;
            }
            items.add(AddonMenuItemDto.builder()
                    .id(id)
                    .slot(slot.toLowerCase(java.util.Locale.ROOT))
                    .label(label)
                    .expose(trimmed(e.get("expose")))
                    .handler(trimmed(e.get("handler")))
                    .kinds(listOrNull(e.get("kinds")))
                    .mimes(listOrNull(e.get("mimes")))
                    .minLevel(trimmed(e.get("minLevel")))
                    .sortIndex(intOrNull(e.get("sortIndex")))
                    .build());
        }
        return items;
    }

    /**
     * A declared list of strings, or {@code null} when the key is absent. The
     * distinction survives to the wire: null means "does not depend on this",
     * which is not the same as an empty list ("matches nothing").
     */
    private static @Nullable List<String> listOrNull(@Nullable Object node) {
        if (node == null) {
            return null;
        }
        return kindIds(node);
    }

    private static @Nullable String trimmed(@Nullable Object v) {
        String s = str(v);
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Kind ids from a {@code kinds:} node. Non-list nodes and blank entries are
     * dropped rather than rejected — a malformed declaration costs the addon
     * its lazy trigger, it must not cost the deployment its boot.
     */
    private static List<String> kindIds(@Nullable Object node) {
        if (!(node instanceof Iterable<?> items)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : items) {
            String id = str(item);
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    private static @Nullable String str(@Nullable Object v) {
        return v == null ? null : v.toString();
    }

    private static @Nullable Integer intOrNull(@Nullable Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
