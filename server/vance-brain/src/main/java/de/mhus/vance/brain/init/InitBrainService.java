package de.mhus.vance.brain.init;

import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Brain startup hook: makes sure the {@value #ACME_TENANT} tenant exists and
 * is populated with the demo/test seed users, project groups and projects.
 *
 * <p>Seed credentials (plaintext, stored hashed via BCrypt):
 * <ul>
 *   <li>{@code marvin.acme} / {@code toon-boss} — future tenant admin</li>
 *   <li>{@code wile.coyote} / {@code acme-rocket}</li>
 *   <li>{@code road.runner} / {@code beep-beep}</li>
 * </ul>
 *
 * <p>Everything is idempotent — {@link TenantService#ensure(String, String)}
 * and the local {@code ensure*} helpers short-circuit on existing records.
 * Runs after {@link TenantService}'s own {@code @PostConstruct} (which creates
 * the {@code default} tenant), so on a fresh database both tenants and their
 * dependents are present by the time the application finishes starting up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InitBrainService {

    public static final String ACME_TENANT = "acme";

    private final TenantService tenantService;
    private final UserService userService;
    private final PasswordService passwordService;
    private final ProjectGroupService projectGroupService;
    private final ProjectService projectService;
    private final TeamService teamService;

    @PostConstruct
    void init() {
        tenantService.ensure(ACME_TENANT, "Acme");

        ensureUser(ACME_TENANT, "marvin.acme", "Marvin Acme", "marvin.acme@mhus.de", "toon-boss");
        ensureUser(ACME_TENANT, "wile.coyote", "Wile E. Coyote", "wile.e.coyote@mhus.de", "acme-rocket");
        ensureUser(ACME_TENANT, "road.runner", "Road Runner", "beep.beep@mhus.com", "beep-beep");

        ensureProjectGroup(ACME_TENANT, "gravity-ignorance", "Department of Gravity Ignorance");
        ensureProject(ACME_TENANT, "instant-hole", "Instant Hole", "gravity-ignorance");
        ensureProject(ACME_TENANT, "dehydrated-boulders", "Dehydrated Boulders", "gravity-ignorance");

        ensureProjectGroup(ACME_TENANT, "high-tech-transport", "High-Tech Transportation");
        ensureProject(ACME_TENANT, "rocket-powered-skates", "Rocket-Powered Skates", "high-tech-transport");
        ensureProject(ACME_TENANT, "giant-slingshot", "Giant Slingshot", "high-tech-transport");

        ensureProjectGroup(ACME_TENANT, "traps-surprises", "Traps & Surprises");
        ensureProject(ACME_TENANT, "invisible-paint", "Invisible Paint", "traps-surprises");
        ensureProject(ACME_TENANT, "iron-seed", "Iron Seed", "traps-surprises");

        ensureProjectGroup(ACME_TENANT, "anvil-logistics", "Anvil Logistics");
        ensureProject(ACME_TENANT, "cloud-delivery", "Cloud Delivery", "anvil-logistics");

        ensureTeam(ACME_TENANT, "rd-propulsion", "R&D & Propulsion", List.of("wile.coyote"));
        ensureTeam(ACME_TENANT, "field-testing-qa", "Field Testing & Quality Assurance",
                List.of("wile.coyote", "road.runner"));
    }

    private void ensureUser(String tenantId, String name, String title, @Nullable String email, String plainPassword) {
        if (userService.existsByTenantAndName(tenantId, name)) {
            return;
        }
        String hash = passwordService.hash(plainPassword);
        userService.create(tenantId, name, hash, title, email);
    }

    private void ensureProjectGroup(String tenantId, String name, String title) {
        if (projectGroupService.existsByTenantAndName(tenantId, name)) {
            return;
        }
        projectGroupService.create(tenantId, name, title);
    }

    private void ensureProject(String tenantId, String name, String title, @Nullable String projectGroupId) {
        if (projectService.existsByTenantAndName(tenantId, name)) {
            return;
        }
        projectService.create(tenantId, name, title, projectGroupId, null);
    }

    private void ensureTeam(String tenantId, String name, String title, List<String> members) {
        if (teamService.existsByTenantAndName(tenantId, name)) {
            return;
        }
        teamService.create(tenantId, name, title, members);
    }
}
