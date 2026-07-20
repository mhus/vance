package de.mhus.vance.addon.brain.desktop;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry in a card's status body (a kanban card, a GTD task, …).
 * {@code deepLink} optionally jumps into the app at this entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("common-desktop")
public class DesktopItem {

    private String title;

    private @Nullable String subtitle;

    /** {@code ok} | {@code attention} | {@code blocked}, or {@code null}. */
    private @Nullable String severity;

    private @Nullable String deepLink;
}
