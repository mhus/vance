package de.mhus.vance.addon.brain.desktop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A small KPI chip on a desktop card. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("common-desktop")
public class DesktopMetric {

    private String label;

    private String value;
}
