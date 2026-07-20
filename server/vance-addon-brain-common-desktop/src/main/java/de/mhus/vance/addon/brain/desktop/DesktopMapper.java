package de.mhus.vance.addon.brain.desktop;

import de.mhus.vance.brain.applications.VanceApplication.AppStatus;
import de.mhus.vance.brain.applications.VanceApplication.StatusItem;
import de.mhus.vance.brain.applications.VanceApplication.StatusMetric;
import de.mhus.vance.brain.applications.VanceApplication.StatusSeverity;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps the brain-side {@code VanceApplication.AppStatus} SPI records to
 * the wire DTOs. Keeps the {@code *View} classes free of SPI types so
 * the generated TypeScript stays clean.
 */
final class DesktopMapper {

    private DesktopMapper() { }

    static DesktopStatusView toView(AppStatus status) {
        List<DesktopMetric> metrics = new ArrayList<>();
        for (StatusMetric m : status.metrics()) {
            metrics.add(DesktopMetric.builder()
                    .label(m.label()).value(m.value()).build());
        }
        List<DesktopItem> items = new ArrayList<>();
        for (StatusItem it : status.items()) {
            items.add(DesktopItem.builder()
                    .title(it.title())
                    .subtitle(it.subtitle())
                    .severity(wire(it.severity()))
                    .deepLink(it.deepLink())
                    .build());
        }
        return DesktopStatusView.builder()
                .headline(status.headline())
                .severity(status.severity().wireName())
                .metrics(metrics)
                .items(items)
                .updatedAt(status.updatedAt() != null ? status.updatedAt().toString() : null)
                .build();
    }

    /** Card body for an app whose {@code status()} threw. */
    static DesktopStatusView errorView(String message) {
        return DesktopStatusView.builder()
                .headline(message)
                .severity(StatusSeverity.BLOCKED.wireName())
                .metrics(new ArrayList<>())
                .items(new ArrayList<>())
                .build();
    }

    private static @Nullable String wire(@Nullable StatusSeverity s) {
        return s != null ? s.wireName() : null;
    }
}
