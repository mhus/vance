package de.mhus.vance.foot.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Contents of {@code .vance/project.yaml} — the non-secret binding that
 * says which brain + tenant + project a working directory belongs to.
 * Safe to commit (it carries no credentials); the token lives in the
 * separate {@link AccessData} / {@code access.yaml}.
 *
 * <p>All fields are optional: a partially-filled binding is valid and the
 * missing pieces fall back to {@code FootConfig} / CLI flags at start-up.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectBinding {

    private @Nullable Brain brain;
    private @Nullable String tenant;
    private @Nullable String project;
    /** Default username offered by {@code /login}; not authoritative for auth. */
    private @Nullable String username;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Brain {
        private @Nullable String httpBase;
        private @Nullable String wsBase;
    }
}
