package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The tools of one service, for a reader who picks a tool to put into a workflow: the
 * integration with everything it offers, each tool with its parameter and result schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ToolServiceDto {

    /** The service name; for tools that belong to none, the name of their source. */
    private String service;

    @Builder.Default
    private List<ToolSpec> tools = new ArrayList<>();
}
