package de.mhus.vance.brain.init;

import de.mhus.vance.shared.document.DocumentService;
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
    private final DocumentService documentService;
    private final InitSettingsLoader initSettingsLoader;

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

        seedInstantHoleDocuments();

        // LLM keys, provider/model defaults, etc. — loaded from a
        // gitignored YAML so a Mongo wipe doesn't lose them.
        initSettingsLoader.loadIfPresent();
    }

    /**
     * Drops two demo documents into the {@code instant-hole} project so the
     * Web-UI document editor has something to show on a fresh database. Both
     * are inline-text and well under the 4 KB threshold.
     */
    private void seedInstantHoleDocuments() {
        ensureDocument(ACME_TENANT, "instant-hole", "notes/welcome.md",
                "Welcome to Instant Hole",
                "text/markdown",
                List.of("welcome", "demo"),
                """
                # Welcome to the Instant Hole project

                You are looking at a seeded demo document — feel free to edit
                or delete it.

                ## What lives here?

                - Field reports from the canyon
                - Specs for hole-deployment tooling
                - Lessons learned (mostly by Mr. Coyote)

                ## Try it out

                1. Open the **+ New document** dialog from this list.
                2. Either type Markdown directly or upload a file.
                3. Inline-stored documents (≤ 4 KB) are editable here; larger
                   files are stored out-of-band and read-only in v1.
                """,
                "marvin.acme");

        ensureDocument(ACME_TENANT, "instant-hole", "specs/deployment-checklist.md",
                "Deployment checklist",
                "text/markdown",
                List.of("spec", "checklist"),
                """
                # Instant Hole — deployment checklist

                Before painting a hole on a flat surface, verify:

                - [ ] Surface is at least 80% flat
                - [ ] Brush is fully loaded with Acme™ Hole Paint
                - [ ] No bystanders within the projected fall radius
                - [ ] Emergency parachute is accessible (operator only)
                - [ ] Field report template is open in this editor

                After deployment:

                - [ ] Confirm hole permeability
                - [ ] Photograph for the field report
                - [ ] Forward report to R&D Propulsion (`rd-propulsion` team)
                """,
                "wile.coyote");
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

    private void ensureDocument(
            String tenantId,
            String projectId,
            String path,
            String title,
            String mimeType,
            List<String> tags,
            String body,
            String createdBy) {
        if (documentService.findByPath(tenantId, projectId, path).isPresent()) {
            return;
        }
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        documentService.create(
                tenantId,
                projectId,
                path,
                title,
                tags,
                mimeType,
                new java.io.ByteArrayInputStream(bytes),
                createdBy);
    }
}
