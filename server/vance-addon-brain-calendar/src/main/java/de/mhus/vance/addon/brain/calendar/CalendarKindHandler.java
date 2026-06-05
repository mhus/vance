package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.shared.document.kind.KindHandler;
import org.springframework.stereotype.Service;

/**
 * Registers the {@code calendar} document kind into the central
 * {@link de.mhus.vance.shared.document.KindRegistry}. Picked up by
 * Spring via the {@link CalendarAddon} component-scan.
 *
 * <p>The actual on-disk parsing / serialisation lives in the
 * {@link CalendarCodec}; this class just stamps {@code "calendar"} as
 * a known kind so {@code doc_create(kind="calendar", …)} resolves
 * cleanly and the kind isn't treated as a typo by the fuzzy resolver.
 */
@Service
public class CalendarKindHandler implements KindHandler {

    @Override
    public String getName() {
        return "calendar";
    }
}
