package de.mhus.vance.brain.tools.manual;

import de.mhus.vance.brain.tools.PodCapabilities;
import de.mhus.vance.shared.document.FrontMatter;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Hides manuals about capabilities this pod does not have. A manual declares them
 * in its header — {@code requires-capabilities: workspace} — and is then neither
 * listed ({@code manual_list}, the {@code how_do_i} catalog) nor readable
 * ({@code manual_read}) where the capability is missing. Same source as the tool
 * gate ({@link PodCapabilities}), so the tools and the manuals about them disappear
 * together ({@code planning/multi-tenant-isolation.md} §4.5).
 */
@Component
public class ManualCapabilityGate {

    static final String HEADER = "requires-capabilities";

    private final PodCapabilities podCapabilities;

    public ManualCapabilityGate(PodCapabilities podCapabilities) {
        this.podCapabilities = podCapabilities;
    }

    /** {@code true} when every capability the manual's header names is available here. */
    public boolean available(@Nullable String manualContent) {
        return podCapabilities.available().containsAll(requiredCapabilities(manualContent));
    }

    /** The comma-separated {@code requires-capabilities} header value; empty when absent. */
    static Set<String> requiredCapabilities(@Nullable String manualContent) {
        String raw = FrontMatter.parse(manualContent).get(HEADER);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }
}
