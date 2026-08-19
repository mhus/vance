package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * What a source can do, as the configuration form needs to know it.
 *
 * <p>The form is capability-driven rather than uniform: {@code selectorMode}
 * decides whether it shows a picker or a text field, and an empty
 * {@code signalsAccepted} hides the report buttons entirely. Showing a control
 * that cannot work is the difference between <i>optional</i> and
 * <i>unreliable</i>.
 */
@GenerateTypeScript("centauri")
public record FeedCapabilitiesView(
        String selectorMode,
        List<String> selectorKinds,
        boolean pushdownTextSearch,
        boolean pushdownLanguage,
        boolean pushdownSince,
        boolean supportsNewerDirection,
        boolean carriesFullBody,
        int maxPageSize,
        List<String> signalsAccepted,
        boolean carriesControlUrl) {}
