package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The stored configuration of one feed — what the configuration tab edits. */
@GenerateTypeScript("centauri")
public record FeedConfigView(
        String folder,
        @Nullable String title,
        List<FeedStreamView> streams,
        FeedFilterView filter,
        int pageSize) {}
