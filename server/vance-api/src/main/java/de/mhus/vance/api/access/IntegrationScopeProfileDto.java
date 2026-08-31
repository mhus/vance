package de.mhus.vance.api.access;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A scope profile offered by the mint form.
 *
 * <p>{@link #surfaces} is carried as display strings ({@code "POST /addon/links/entry"})
 * so a person can see what they are about to hand out. It is documentation, not
 * an input: the server matches against the profile's own declaration, never
 * against anything a client sends back.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("access")
public class IntegrationScopeProfileDto {

    private String id;

    private String label;

    private boolean requiresProject;

    private List<String> surfaces;
}
