package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.brain.agrajag.ToolErrorPatternResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserService;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 *   <li>{@code _acme-automaton} / {@code anvils-fall-down} — service account
 *       for headless foot daemons (see {@code planning/foot-daemon-tools.md}).
 *       Username starts with {@code _} per the service-account convention
 *       so any code that needs to differentiate human users from bots can
 *       check {@code username.startsWith("_")}.</li>
 * </ul>
 *
 * <p>Everything is idempotent — {@link TenantService#ensure(String, String)}
 * and the local {@code ensure*} helpers short-circuit on existing records.
 * Runs after {@link TenantService}'s own {@code @PostConstruct} (which creates
 * the {@code default} tenant), so on a fresh database both tenants and their
 * dependents are present by the time the application finishes starting up.
 *
 * <p>Gated by {@link BootstrapProperties#isAcme()} (default {@code true}).
 * Set {@code vance.bootstrap.acme=false} in {@code application.yml} (or via
 * {@code VANCE_BOOTSTRAP_ACME=false}) for clean production deployments —
 * the entire {@link #init()} method then short-circuits to a no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BootstrapBrainService {

    public static final String ACME_TENANT = "acme";

    private final TenantService tenantService;
    private final UserService userService;
    private final PasswordService passwordService;
    private final ProjectGroupService projectGroupService;
    private final ProjectService projectService;
    private final TeamService teamService;
    private final DocumentService documentService;
    private final SettingService settingService;
    private final HomeBootstrapService homeBootstrapService;
    private final InitSettingsLoader initSettingsLoader;
    private final BootstrapProperties properties;
    // Inject the migration so its @PostConstruct fires before this service's
    // @PostConstruct does. Without this dependency, the bootstrap would create
    // a fresh _tenant project alongside the legacy _vance project on a
    // pre-migration dev Mongo, and the migration would crash on the unique
    // (tenantId, name) index when trying to rename.
    @SuppressWarnings("unused")
    private final VanceToTenantProjectMigration tenantProjectMigration;

    @PostConstruct
    void init() {
        if (!properties.isAcme()) {
            log.info("Bootstrap: vance.bootstrap.acme=false — skipping Acme demo seed");
            return;
        }
        tenantService.ensure(ACME_TENANT, "Acme");

        ensureUser(ACME_TENANT, "marvin.acme", "Marvin Acme", "marvin.acme@mhus.de", "toon-boss");
        ensureUser(ACME_TENANT, "wile.coyote", "Wile E. Coyote", "wile.e.coyote@mhus.de", "acme-rocket");
        ensureUser(ACME_TENANT, "road.runner", "Road Runner", "beep.beep@mhus.com", "beep-beep");
        // Service account for headless foot daemons. The leading
        // underscore routes through UserService.createServiceAccount,
        // which marks the user as a bot (serviceAccount=true) and
        // defaults loginEnabled to false. We then flip loginEnabled
        // back to true for this specific account because the foot
        // daemon launcher (`deployment/local/vance-daemon`) needs the
        // standard password-login endpoint to mint its JWT —
        // {serviceAccount=true, loginEnabled=true} is a valid combo
        // since the two flags are orthogonal.
        ensureUser(ACME_TENANT, "_acme-automaton", "Acme Automaton",
                "automaton@acme.invalid", "anvils-fall-down");
        userService.setLoginEnabled(ACME_TENANT, "_acme-automaton", true);

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

        // The tenant-wide _vance system project hosts settings and
        // server-tool defaults. Provision it eagerly so that
        // InitSettingsLoader has somewhere to write — other tenants
        // get _vance lazily on first login (AccessController).
        homeBootstrapService.ensureTenantProject(ACME_TENANT);

        // Seed the mock OAuth provider so the local-dev compose stack
        // works out of the box: start `deployment/local` docker compose,
        // then "Connect mock" in the Web-UI completes the OAuth dance
        // against http://localhost:18099 without any manual setup.
        ensureMockOAuthProvider(ACME_TENANT);

        // Seed the bundled tool-error pattern document so tenant admins
        // see + can edit the rules through the regular document editor.
        // The resolver still falls back to the classpath version when
        // the doc is missing — this is just for discoverability and
        // tenant-level overrides. See specification/agrajag-engine.md §4.2.
        ensureAgrajagErrorPatternDocument(ACME_TENANT);

        // LLM keys, provider/model defaults, etc. — loaded from a
        // gitignored YAML so a Mongo wipe doesn't lose them. The
        // entries land on the tenant's _vance project (see
        // InitSettingsLoader).
        initSettingsLoader.loadIfPresent();
    }

    /**
     * YAML body for the mock OAuth provider document
     * ({@code oauth/mock.yaml} in the {@code _tenant} project) — points
     * at the {@code navikt/mock-oauth2-server} instance from
     * {@code deployment/local/docker-compose.yaml}.
     */
    private static final String MOCK_OAUTH_PROVIDER_YAML = """
            # Mock OAuth provider (navikt/mock-oauth2-server) for local development.
            # Started by deployment/local/docker-compose.yaml on port 18099. The
            # mock validates nothing — any clientId/secret works, any username
            # entered at the login prompt becomes the JWT subject. This is the
            # zero-config entry-point for trying out the OAuth flow without
            # registering a real provider.
            type: oidc
            clientId: vance-local
            discoveryUrl: "http://localhost:18099/default/.well-known/openid-configuration"
            scopes:
              - openid
              - profile
              - email
            """;

    private static final String MOCK_OAUTH_PROVIDER_ID = "mock";

    /**
     * Drops the bundled {@code agrajag/error-patterns.yaml} into the
     * tenant's system project on first boot so admins can edit it
     * through the regular document editor. The
     * {@link ToolErrorPatternResolver} reads the bundled file as the
     * cascade root regardless — this seed only exists for visibility
     * and tenant-level overrides. Idempotent.
     */
    private void ensureAgrajagErrorPatternDocument(String tenantId) {
        String tenantProject = HomeBootstrapService.TENANT_PROJECT_NAME;
        String docPath = ToolErrorPatternResolver.DOCUMENT_PATH;
        if (documentService.findByPath(tenantId, tenantProject, docPath).isPresent()) {
            return;
        }
        String body = readClasspathResource(ToolErrorPatternResolver.BUNDLED_RESOURCE);
        if (body == null) {
            log.warn("Bootstrap: bundled agrajag patterns resource missing — skipping seed");
            return;
        }
        documentService.createText(
                tenantId, tenantProject, docPath,
                "Agrajag error patterns",
                List.of("agrajag", "tools", "config"),
                body,
                "bootstrap");
        log.info("Bootstrap: seeded agrajag error-patterns document '{}'", docPath);
    }

    private static @Nullable String readClasspathResource(String resource) {
        try (InputStream in = BootstrapBrainService.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Provision a ready-to-go mock OAuth provider in the {@code _tenant}
     * project so a fresh dev database lets the user "Connect mock" in
     * the Web-UI immediately. The {@code client_secret} value is
     * irrelevant — the mock doesn't validate it; the setting just has
     * to exist so {@link OAuthAdminController}/{@link OAuthController}'s
     * "client secret missing" guard doesn't trip.
     *
     * <p>Idempotent on both records — re-runs leave existing edits
     * alone.
     */
    private void ensureMockOAuthProvider(String tenantId) {
        String tenantProject = HomeBootstrapService.TENANT_PROJECT_NAME;
        String docPath = "_vance/oauth/" + MOCK_OAUTH_PROVIDER_ID + ".yaml";
        String secretKey = "oauth." + MOCK_OAUTH_PROVIDER_ID + ".client_secret";

        if (documentService.findByPath(tenantId, tenantProject, docPath).isEmpty()) {
            documentService.createText(
                    tenantId, tenantProject, docPath,
                    "Mock OAuth (local)",
                    List.of("oauth", "dev"),
                    MOCK_OAUTH_PROVIDER_YAML,
                    "bootstrap");
            log.info("Bootstrap: seeded mock OAuth provider document '{}'", docPath);
        }

        boolean hasSecret = settingService.getDecryptedPassword(
                tenantId, SettingService.SCOPE_PROJECT, tenantProject, secretKey) != null;
        if (!hasSecret) {
            settingService.setEncryptedPassword(
                    tenantId, SettingService.SCOPE_PROJECT, tenantProject,
                    secretKey,
                    "dummy-secret-mock-does-not-check");
            log.info("Bootstrap: seeded mock OAuth client secret '{}'", secretKey);
        }
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
        if (name.startsWith(UserService.SERVICE_ACCOUNT_PREFIX)) {
            // Service accounts have loginEnabled=false hardcoded by
            // createServiceAccount — tokens for them have to be minted
            // out-of-band (Anus admin shell). The password hash is
            // stored anyway so a future "promote to login-able" flow
            // can flip the flag without password reset.
            userService.createServiceAccount(tenantId, name, hash, title, email);
        } else {
            userService.create(tenantId, name, hash, title, email);
        }
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
