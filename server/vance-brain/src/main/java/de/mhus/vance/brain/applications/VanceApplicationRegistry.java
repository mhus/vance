package de.mhus.vance.brain.applications;

import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Index of {@link VanceApplication} beans, keyed by {@code appName()}.
 *
 * <p>Spring auto-wires every {@link VanceApplication} implementation
 * in the classpath into the constructor list and we build a lower-
 * cased lookup map at startup. Calling {@link #require(String)} from
 * the generic {@code app_rebuild} tool gives the right service for
 * the {@code $meta.app} value in the folder's manifest.
 */
@Service
@Slf4j
public class VanceApplicationRegistry {

    private final Map<String, VanceApplication> byName;

    public VanceApplicationRegistry(List<VanceApplication> apps) {
        Map<String, VanceApplication> map = new LinkedHashMap<>();
        for (VanceApplication app : apps) {
            String key = app.appName().toLowerCase(Locale.ROOT);
            VanceApplication previous = map.put(key, app);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two VanceApplication beans claim appName '" + key
                                + "': " + previous.getClass().getName()
                                + " and " + app.getClass().getName());
            }
        }
        this.byName = Map.copyOf(map);
        log.info("VanceApplicationRegistry initialised with {} apps: {}",
                byName.size(), new TreeSet<>(byName.keySet()));
    }

    /**
     * Look up an app by its discriminator without throwing. Used by the
     * engine drain when applying per-message {@code activeApp} hints —
     * unknown app types degrade silently (no prompt-inject) rather than
     * killing the turn.
     */
    public java.util.Optional<VanceApplication> find(String appName) {
        if (appName == null || appName.isBlank()) return java.util.Optional.empty();
        VanceApplication app = byName.get(appName.toLowerCase(Locale.ROOT));
        return java.util.Optional.ofNullable(app);
    }

    /** Look up an app by its discriminator. Throws when unknown. */
    public VanceApplication require(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new ToolException(
                    "App folder has no `$meta.app` value — manifest is "
                            + "incomplete. Known apps: " + new TreeSet<>(byName.keySet()));
        }
        VanceApplication app = byName.get(appName.toLowerCase(Locale.ROOT));
        if (app == null) {
            throw new ToolException(
                    "Unknown application type '" + appName + "'. Known: "
                            + new TreeSet<>(byName.keySet()));
        }
        return app;
    }

    public java.util.Set<String> knownAppNames() {
        return new TreeSet<>(byName.keySet());
    }
}
