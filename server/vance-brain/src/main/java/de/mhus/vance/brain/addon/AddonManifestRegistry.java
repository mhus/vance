package de.mhus.vance.brain.addon;

import de.mhus.vance.api.addon.AddonTileDto;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads every addon's {@code META-INF/vance-addon.yaml} from the classpath once
 * at startup and exposes the declarative {@code tile:} block per addon id. The
 * addon manifest is the single source of the landing-tile metadata — the same
 * file the dev-server middleware reads — so {@code GET /face/addons} carries the
 * tile in production just as the dev stand-in does. Addons without a
 * {@code tile:} block simply have no launcher tile.
 */
@Component
@Slf4j
public class AddonManifestRegistry {

    private final Map<String, AddonTileDto> tilesById;

    public AddonManifestRegistry(ResourcePatternResolver resourceResolver) {
        Map<String, AddonTileDto> map = new HashMap<>();
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
        log.info("AddonManifestRegistry: {} addon tile(s) discovered", tilesById.size());
    }

    /** The declarative tile for {@code addonId}, or {@code null} when it declares none. */
    public @Nullable AddonTileDto tileFor(String addonId) {
        return tilesById.get(addonId);
    }

    private static @Nullable String str(@Nullable Object v) {
        return v == null ? null : v.toString();
    }
}
