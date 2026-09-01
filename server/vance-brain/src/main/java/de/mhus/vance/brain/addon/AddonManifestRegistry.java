package de.mhus.vance.brain.addon;

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
 * loaded any federation remote: {@code tile:}, {@code profile:} and
 * {@code kinds:}. The addon manifest is the single source for all three — the
 * same file the dev-server middleware reads — so {@code GET /face/addons}
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

    private final Map<String, AddonTileDto> tilesById;
    private final Map<String, AddonProfileTabDto> profileTabsById;
    private final Map<String, List<String>> kindsById;
    private final java.util.Set<String> eagerIds;

    public AddonManifestRegistry(ResourcePatternResolver resourceResolver) {
        Map<String, AddonTileDto> map = new HashMap<>();
        Map<String, AddonProfileTabDto> profiles = new HashMap<>();
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
        this.kindsById = Map.copyOf(kinds);
        this.eagerIds = java.util.Set.copyOf(eager);
        log.info("AddonManifestRegistry: {} addon tile(s), {} profile tab(s), {} kind-contributing addon(s), "
                        + "{} eager addon(s) discovered",
                tilesById.size(), profileTabsById.size(), kindsById.size(), eagerIds.size());
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
