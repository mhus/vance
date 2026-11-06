package de.mhus.vance.api.tools;

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
 * What the catalog says about a service, in the reader's language: what it is, which login its tools need, and
 * which of its tools to look at first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ServiceCatalogDto {

    /** The entry's name — what {@code POST workflow-triggers} takes as {@code service}. */
    private String name;

    private String title;

    private String description;

    /** An icon name of the web UI. */
    private @Nullable String icon;

    /** The OAuth provider whose login of the calling user the tools need; {@code null} when none. */
    private @Nullable String loginProvider;

    /**
     * Whether the calling user has connected {@link #loginProvider}. {@code null} when the service needs no user
     * login, or when the caller is not a user.
     */
    private @Nullable Boolean connected;

    /** The manual that says how to connect the service. */
    private @Nullable String manual;

    @Builder.Default
    private List<ServiceCatalogHighlightDto> highlights = new ArrayList<>();

    /** What the service can start a workflow with. */
    @Builder.Default
    private List<ServiceCatalogTriggerDto> triggers = new ArrayList<>();
}
