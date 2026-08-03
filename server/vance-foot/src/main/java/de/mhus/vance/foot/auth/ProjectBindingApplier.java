package de.mhus.vance.foot.auth;

import de.mhus.vance.foot.config.FootConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Overlays a {@link ProjectBinding} (from {@code .vancetope/project.eddie.yaml}) onto
 * the running {@link FootConfig}. Every non-blank field wins over the
 * {@code application.yaml} default; blank/absent fields are left untouched.
 *
 * <p>Precedence at start-up is {@code application.yaml < project.eddie.yaml < CLI
 * flags} — this applier runs before the flag overrides in
 * {@code VanceFootCommand}. Used both on start-up and right after a
 * {@code /login} rewrites the binding.
 */
@Component
@Slf4j
public class ProjectBindingApplier {

    /** Applies non-blank binding fields onto {@code config}. */
    public void apply(ProjectBinding binding, FootConfig config) {
        ProjectBinding.Brain brain = binding.getBrain();
        if (brain != null) {
            if (isSet(brain.getHttpBase())) {
                config.getBrain().setHttpBase(brain.getHttpBase().trim());
            }
            if (isSet(brain.getWsBase())) {
                config.getBrain().setWsBase(brain.getWsBase().trim());
            }
        }
        if (isSet(binding.getTenant())) {
            config.getAuth().setTenant(binding.getTenant().trim());
        }
        if (isSet(binding.getUsername())) {
            config.getAuth().setUsername(binding.getUsername().trim());
        }
        if (isSet(binding.getProject())) {
            // Setting a project id arms the welcome-time auto-bootstrap so a
            // directory with a stored binding boots straight into its project.
            config.getBootstrap().setProjectId(binding.getProject().trim());
        }
        log.debug("applied project binding: tenant={} project={} httpBase={}",
                config.getAuth().getTenant(),
                config.getBootstrap().getProjectId(),
                config.getBrain().getHttpBase());
    }

    private static boolean isSet(@org.jspecify.annotations.Nullable String value) {
        return value != null && !value.isBlank();
    }
}
