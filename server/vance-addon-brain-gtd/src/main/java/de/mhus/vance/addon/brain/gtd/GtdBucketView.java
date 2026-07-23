package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO for one derived bucket and its actions. */
@GenerateTypeScript("gtd")
public record GtdBucketView(
        String bucket,
        String title,
        List<GtdActionView> actions) {}
