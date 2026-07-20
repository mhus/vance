package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO for {@code GET /backlinks} — "what links here" for one page. */
@GenerateTypeScript("wiki")
public record WikiBacklinksView(
        String path,
        List<WikiPageView> inbound) {}
