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
 * Sent by a connected client to declare which tools it exposes for this
 * session. Overwrites any previous registration for the same session —
 * clients re-send the full list whenever their tool surface changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ClientToolRegisterRequest {

    @Builder.Default
    private List<ToolSpec> tools = new ArrayList<>();
}
