package de.mhus.vance.addon.brain.desktop;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Dynamic body an app contributes to its desktop card. {@code severity}
 * is the wire form of {@code VanceApplication.StatusSeverity}
 * ({@code ok} / {@code attention} / {@code blocked}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("common-desktop")
public class DesktopStatusView {

    private @Nullable String headline;

    /** {@code ok} | {@code attention} | {@code blocked}. */
    private String severity;

    @Builder.Default
    private List<DesktopMetric> metrics = new ArrayList<>();

    @Builder.Default
    private List<DesktopItem> items = new ArrayList<>();

    /** ISO-8601 instant, or {@code null}. */
    private @Nullable String updatedAt;
}
