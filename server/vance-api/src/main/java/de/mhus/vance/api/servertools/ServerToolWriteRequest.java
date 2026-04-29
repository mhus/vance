package de.mhus.vance.api.servertools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write payload for creating or updating a server tool. The {@code name}
 * is part of the URL; everything else lives in the body. Server-side
 * validation ensures {@code type} matches a registered
 * {@code ToolFactory}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("server-tools")
public class ServerToolWriteRequest {

    @NotBlank
    private String type;

    @NotBlank
    private String description;

    @Builder.Default
    private Map<String, Object> parameters = new LinkedHashMap<>();

    @Builder.Default
    private List<String> labels = new ArrayList<>();

    private boolean enabled;

    private boolean primary;
}
