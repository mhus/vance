package de.mhus.vance.api.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tail of one stdout / stderr stream of an execution. The
 * {@code lines} list is already split on line terminators; the
 * {@code stream} field tells the UI which channel it represents
 * (mirrored back from the request to keep call/response symmetrical).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("execution")
public class ExecutionTailDto {

    /** The execution id the lines were tailed from. */
    private String id;

    /** {@code "stdout"} or {@code "stderr"}. */
    private String stream;

    /** Number of lines the caller asked for. */
    private int requested;

    @Builder.Default
    private List<String> lines = new ArrayList<>();
}
