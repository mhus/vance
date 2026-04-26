package de.mhus.vance.api.thinkprocess;

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
 * Reply to {@code process-list} — every think-process in the bound
 * session, oldest first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessListResponse {

    @Builder.Default
    private List<ProcessSummary> processes = new ArrayList<>();

    /**
     * How many processes the server skipped because they are in a
     * terminal state and the request didn't ask for them. {@code null}
     * when nothing was hidden — the client can show a hint like
     * "(2 terminated hidden — /process-list --all)" when this is set.
     */
    private @Nullable Integer hiddenTerminated;
}
