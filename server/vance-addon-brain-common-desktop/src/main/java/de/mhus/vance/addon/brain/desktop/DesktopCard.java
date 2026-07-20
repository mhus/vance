package de.mhus.vance.addon.brain.desktop;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One app on the desktop: a launcher (icon + title + open link) plus
 * an optional dynamic {@link DesktopStatusView status} body. Apps
 * without a status still get a card — the desktop is a launcher too.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("common-desktop")
public class DesktopCard {

    /** Manifest document id — the web view opens the app by this id. */
    private @Nullable String id;

    /** App-type discriminator ({@code $meta.app}). */
    private String app;

    /** Folder holding this app's {@code _app.yaml}. */
    private String folder;

    private String title;

    private @Nullable String description;

    /** Icon string from the app's {@code describe()} — emoji, named
     *  token, or {@code vance:}/{@code http} image link. Resolved
     *  generically by the frontend, never per app type. */
    private String icon;

    /** Deep-link that opens the app ({@code vance:}-URI). */
    private String openLink;

    /** Dynamic body — {@code null} when the app has no desktop status. */
    private @Nullable DesktopStatusView status;
}
