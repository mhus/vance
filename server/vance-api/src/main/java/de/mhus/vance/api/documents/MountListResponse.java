package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The mounted external sources of a project.
 *
 * <p>Returned by {@code GET /brain/{tenant}/mounts?projectId=}. Empty for
 * every project without mounts, which is most of them — and empty is also the
 * answer in a process that has no Jaglan implementation at all.
 *
 * <p>Answered from configuration plus the capabilities cache, so it is cheap
 * enough for a view to load alongside its folder listing. A cold cache reports
 * {@code UNKNOWN} access and no item count rather than fetching, which is why
 * those two fields may fill in on a later call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class MountListResponse {

    @Builder.Default
    private List<MountDto> mounts = new ArrayList<>();
}
