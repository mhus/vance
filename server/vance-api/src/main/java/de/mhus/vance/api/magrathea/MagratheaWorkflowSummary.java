package de.mhus.vance.api.magrathea;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Compact list-view entry for the workflows list in the Web-UI insights
 * editor. Mirror of {@link MagratheaWorkflowDto} without the YAML body —
 * the listing renders identity / counts / tags only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("magrathea")
public class MagratheaWorkflowSummary {

    private String name;
    private @Nullable String description;
    private @Nullable String version;
    private MagratheaWorkflowSource source;

    /** Size of the {@code parameters:} block — helps the UI label "Trigger" vs "Trigger…". */
    private int paramCount;

    /** Size of the {@code states:} block. */
    private int stateCount;

    private @Nullable List<String> tags;
}
