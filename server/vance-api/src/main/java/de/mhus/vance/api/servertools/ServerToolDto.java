package de.mhus.vance.api.servertools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read view of a configured server tool. Carries the persisted fields
 * plus the owning project so the UI can render breadcrumbs / cascade
 * hints. Bundled bean tools are <b>not</b> represented here — they
 * live in code and are not editable through this endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("server-tools")
public class ServerToolDto {

    private String name;

    private String type;

    private String description;

    @Builder.Default
    private Map<String, Object> parameters = new LinkedHashMap<>();

    @Builder.Default
    private List<String> labels = new ArrayList<>();

    private boolean enabled;

    private boolean primary;

    /** Owning project — {@code _vance} for system-wide tools. */
    private String projectId;

    /** Last update timestamp in millis since epoch; {@code null} for unsaved drafts. */
    private @Nullable Long updatedAtTimestamp;

    /** Creator user login, if recorded. */
    private @Nullable String createdBy;
}
