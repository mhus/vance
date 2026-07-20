package de.mhus.vance.addon.brain.desktop;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Full state of a Common Desktop — what {@code GET .../desktop/status}
 * returns. One {@link DesktopCard} per app found under the desktop's
 * scan root, in presentation order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("common-desktop")
public class DesktopView {

    private String folder;

    @Builder.Default
    private List<DesktopCard> cards = new ArrayList<>();
}
