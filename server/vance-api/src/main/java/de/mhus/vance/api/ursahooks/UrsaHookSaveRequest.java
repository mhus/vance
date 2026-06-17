package de.mhus.vance.api.ursahooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wire payload for {@code PUT
 * /brain/{tenant}/project/{project}/hooks/{event}/{name}} — the editor
 * roundtrips the raw YAML; everything else is parsed server-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ursahooks")
public class UrsaHookSaveRequest {

    @NotBlank
    private String yaml;
}
