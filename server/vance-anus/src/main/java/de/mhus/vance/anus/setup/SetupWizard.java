package de.mhus.vance.anus.setup;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Interactive setup wizard for {@code anus --setup}.
 *
 * <p>Flow:
 * <ol>
 *   <li>Print every tenant + its users so the operator sees the current
 *       state (in particular: catches an accidental
 *       {@code vance.bootstrap.acme=true} on the Brain side).</li>
 *   <li>If at least one non-system tenant exists, prompt to select one
 *       or create a fresh tenant; otherwise jump straight into the
 *       "create tenant" sub-wizard.</li>
 *   <li>Same for the user inside the chosen tenant.</li>
 *   <li>Show the setup menu with all current values; let the operator
 *       edit individual entries until they pick {@code s) Save} or
 *       {@code q) Quit}.</li>
 *   <li>On save: persist tenant ({@code ensure}), {@code _tenant} project,
 *       user (create or update), and all AI-provider / research settings.</li>
 * </ol>
 *
 * <p>The wizard ignores the {@value TenantService#SYSTEM_TENANT} tenant
 * in listings and selection — it's an internal bootstrap artefact, not
 * something an operator picks.
 */
@Component
@Slf4j
public class SetupWizard {

    private final TenantService tenantService;
    private final UserService userService;
    private final PasswordService passwordService;
    private final SettingService settingService;
    private final HomeBootstrapService homeBootstrapService;
    // ObjectProvider mirrors the AccessCommands trick — the LineReader bean
    // depends transitively on the command catalog, so resolving it lazily
    // breaks the wiring cycle Spring Boot 4 otherwise rejects.
    private final ObjectProvider<LineReader> lineReaderProvider;

    public SetupWizard(
            TenantService tenantService,
            UserService userService,
            PasswordService passwordService,
            SettingService settingService,
            HomeBootstrapService homeBootstrapService,
            ObjectProvider<LineReader> lineReaderProvider) {
        this.tenantService = tenantService;
        this.userService = userService;
        this.passwordService = passwordService;
        this.settingService = settingService;
        this.homeBootstrapService = homeBootstrapService;
        this.lineReaderProvider = lineReaderProvider;
    }

    public void run() {
        LineReader reader = lineReaderProvider.getObject();
        PrintWriter out = reader.getTerminal().writer();

        out.println();
        out.println("Vance Setup");
        out.println("===========");
        out.println();

        List<TenantDocument> tenants = listOperatorTenants();
        printOverview(out, tenants);

        SetupState state = new SetupState();
        if (!selectTenant(out, reader, tenants, state)) {
            return;
        }
        if (!selectUser(out, reader, state)) {
            return;
        }
        loadProviderDefaults(state);

        if (!setupMenu(out, reader, state)) {
            out.println("Setup cancelled. No changes written.");
            out.flush();
            return;
        }

        save(out, state);
    }

    // ──────────────────────── overview ────────────────────────

    private List<TenantDocument> listOperatorTenants() {
        return tenantService.all().stream()
                .filter(t -> !TenantService.SYSTEM_TENANT.equals(t.getName()))
                .sorted(Comparator.comparing(TenantDocument::getName))
                .toList();
    }

    private void printOverview(PrintWriter out, List<TenantDocument> tenants) {
        out.println("Existing tenants:");
        if (tenants.isEmpty()) {
            out.println("  (none)");
            out.println();
            out.flush();
            return;
        }
        int idx = 1;
        for (TenantDocument t : tenants) {
            String provider = settingService.getStringValue(
                    t.getName(),
                    SettingService.SCOPE_PROJECT,
                    HomeBootstrapService.TENANT_PROJECT_NAME,
                    "ai.default.provider");
            String providerLabel = provider != null && !provider.isBlank() ? provider : "—";
            out.printf("  >> [%d] Tenant: %s - %s (%s)%n",
                    idx,
                    t.getName(),
                    StringUtils.defaultIfBlank(t.getTitle(), t.getName()),
                    providerLabel);
            List<UserDocument> users = userService.all(t.getName()).stream()
                    .sorted(Comparator.comparing(UserDocument::getName))
                    .toList();
            if (users.isEmpty()) {
                out.println("     (no users)");
            } else {
                for (UserDocument u : users) {
                    out.printf("     - User: %s - %s - %s%n",
                            u.getName(),
                            StringUtils.defaultIfBlank(u.getTitle(), u.getName()),
                            StringUtils.defaultIfBlank(u.getEmail(), "—"));
                }
            }
            idx++;
        }
        out.println();
        out.flush();
    }

    // ──────────────────────── tenant selection ────────────────────────

    /** @return {@code false} if the operator quit. */
    private boolean selectTenant(PrintWriter out, LineReader reader,
            List<TenantDocument> tenants, SetupState state) {
        if (tenants.isEmpty()) {
            out.println("No tenants yet — let's create one.");
            out.flush();
            return createTenant(out, reader, state);
        }
        while (true) {
            String prompt = "Select tenant [1-" + tenants.size() + "], c) Create new, q) Quit: ";
            String in = readLine(reader, prompt);
            if (in == null || "q".equalsIgnoreCase(in)) {
                return false;
            }
            if ("c".equalsIgnoreCase(in)) {
                return createTenant(out, reader, state);
            }
            Integer pick = parsePositiveInt(in);
            if (pick != null && pick >= 1 && pick <= tenants.size()) {
                TenantDocument t = tenants.get(pick - 1);
                state.setTenantId(t.getName());
                state.setTenantTitle(t.getTitle());
                state.setTenantCreated(false);
                return true;
            }
            out.println("Invalid choice.");
            out.flush();
        }
    }

    /** @return {@code false} if the operator quit. */
    private boolean createTenant(PrintWriter out, LineReader reader, SetupState state) {
        String name;
        while (true) {
            name = readLine(reader, "New tenant name (lowercase, e.g. acme): ");
            if (name == null || "q".equalsIgnoreCase(name)) {
                return false;
            }
            name = name.trim();
            if (name.isEmpty()) {
                out.println("Name must not be empty.");
                out.flush();
                continue;
            }
            if (TenantService.SYSTEM_TENANT.equals(name)) {
                out.println("'" + TenantService.SYSTEM_TENANT
                        + "' is reserved for internal use — pick another name.");
                out.flush();
                continue;
            }
            if (tenantService.findByName(name).isPresent()) {
                out.println("Tenant '" + name + "' already exists — pick another or restart.");
                out.flush();
                continue;
            }
            break;
        }
        String title = readLine(reader, "Tenant title (display name, optional): ");
        state.setTenantId(name);
        state.setTenantTitle(StringUtils.isBlank(title) ? name : title.trim());
        state.setTenantCreated(true);
        return true;
    }

    // ──────────────────────── user selection ────────────────────────

    /** @return {@code false} if the operator quit. */
    private boolean selectUser(PrintWriter out, LineReader reader, SetupState state) {
        List<UserDocument> users;
        if (state.isTenantCreated()) {
            users = List.of();
        } else {
            users = userService.all(state.getTenantId()).stream()
                    .filter(u -> !u.isServiceAccount())
                    .sorted(Comparator.comparing(UserDocument::getName))
                    .toList();
        }
        if (users.isEmpty()) {
            out.println();
            out.println("No users in tenant '" + state.getTenantId()
                    + "' — let's create one.");
            out.flush();
            return createUser(out, reader, state);
        }
        out.println();
        out.println("Users in tenant '" + state.getTenantId() + "':");
        int idx = 1;
        for (UserDocument u : users) {
            out.printf("  >> [%d] %s - %s - %s%n",
                    idx,
                    u.getName(),
                    StringUtils.defaultIfBlank(u.getTitle(), u.getName()),
                    StringUtils.defaultIfBlank(u.getEmail(), "—"));
            idx++;
        }
        while (true) {
            String prompt = "Select user [1-" + users.size() + "], c) Create new, q) Quit: ";
            String in = readLine(reader, prompt);
            if (in == null || "q".equalsIgnoreCase(in)) {
                return false;
            }
            if ("c".equalsIgnoreCase(in)) {
                return createUser(out, reader, state);
            }
            Integer pick = parsePositiveInt(in);
            if (pick != null && pick >= 1 && pick <= users.size()) {
                UserDocument u = users.get(pick - 1);
                state.setUserName(u.getName());
                state.setUserTitle(u.getTitle());
                state.setUserEmail(u.getEmail());
                state.setUserCreated(false);
                return true;
            }
            out.println("Invalid choice.");
            out.flush();
        }
    }

    /** @return {@code false} if the operator quit. */
    private boolean createUser(PrintWriter out, LineReader reader, SetupState state) {
        String name;
        while (true) {
            name = readLine(reader, "New user name (login, lowercase, e.g. wile.coyote): ");
            if (name == null || "q".equalsIgnoreCase(name)) {
                return false;
            }
            name = name.trim();
            if (name.isEmpty()) {
                out.println("Name must not be empty.");
                out.flush();
                continue;
            }
            if (name.startsWith(UserService.SERVICE_ACCOUNT_PREFIX)) {
                out.println("Names starting with '" + UserService.SERVICE_ACCOUNT_PREFIX
                        + "' are reserved for service accounts — pick a regular login.");
                out.flush();
                continue;
            }
            if (!state.isTenantCreated()
                    && userService.existsByTenantAndName(state.getTenantId(), name)) {
                out.println("User '" + name + "' already exists in tenant '"
                        + state.getTenantId() + "'.");
                out.flush();
                continue;
            }
            break;
        }
        String title = readLine(reader, "User title (display name, optional): ");
        String email = readLine(reader, "Email (optional): ");
        String password;
        while (true) {
            password = readPassword(reader, "Password: ");
            if (StringUtils.isBlank(password)) {
                out.println("Password must not be empty.");
                out.flush();
                continue;
            }
            String confirm = readPassword(reader, "Confirm: ");
            if (!password.equals(confirm)) {
                out.println("Mismatch — try again.");
                out.flush();
                continue;
            }
            break;
        }
        state.setUserName(name);
        state.setUserTitle(StringUtils.isBlank(title) ? name : title.trim());
        state.setUserEmail(StringUtils.isBlank(email) ? null : email.trim());
        state.setUserPassword(password);
        state.setUserCreated(true);
        return true;
    }

    // ──────────────────────── provider defaults ────────────────────────

    private void loadProviderDefaults(SetupState state) {
        // Existing tenants may already have AI settings — read them so the
        // menu shows current values instead of forcing the operator to
        // re-type. The wizard never reads PASSWORD settings back into the
        // plaintext field (we'd have to decrypt + redisplay, which is more
        // exposure than the convenience is worth). API-key fields therefore
        // start blank: leaving them blank in the menu means "keep existing".
        if (state.isTenantCreated()) {
            state.setProvider(ProviderPreset.GEMINI);
            state.setAiModel(ProviderPreset.GEMINI.defaultModel());
            return;
        }
        String tenantId = state.getTenantId();
        String providerId = settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, "ai.default.provider");
        ProviderPreset preset = providerId == null ? null
                : ProviderPreset.fromSettingsId(providerId);
        if (preset == null) {
            preset = ProviderPreset.GEMINI;
        }
        state.setProvider(preset);
        String model = settingService.getStringValue(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME, "ai.default.model");
        state.setAiModel(StringUtils.defaultIfBlank(model, preset.defaultModel()));
    }

    // ──────────────────────── setup menu ────────────────────────

    /** @return {@code true} if the operator picked {@code s) Save}. */
    private boolean setupMenu(PrintWriter out, LineReader reader, SetupState state) {
        while (true) {
            out.println();
            out.println("Setup");
            out.println("-----");
            out.printf("  1) Tenant:               %s%n", state.getTenantId()
                    + (state.isTenantCreated() ? "  (new)" : ""));
            out.printf("  2) Tenant title:         %s%n",
                    StringUtils.defaultIfBlank(state.getTenantTitle(), "—"));
            out.printf("  3) Username:             %s%n", state.getUserName()
                    + (state.isUserCreated() ? "  (new)" : ""));
            out.printf("  4) User title:           %s%n",
                    StringUtils.defaultIfBlank(state.getUserTitle(), "—"));
            out.printf("  5) User email:           %s%n",
                    StringUtils.defaultIfBlank(state.getUserEmail(), "—"));
            out.printf("  6) AI provider:          %s%n", state.getProvider().displayName());
            out.printf("  7) AI model:             %s%n", state.getAiModel());
            out.printf("  8) AI API key:           %s%n", maskedFor(state.getAiApiKey(),
                    !state.isTenantCreated()));
            if (state.getProvider().supportsEmbedding()) {
                out.printf("  9) Embedding API key:    %s  (reuses chat key if blank)%n",
                        maskedFor(state.getEmbeddingApiKey(), !state.isTenantCreated()));
            } else {
                out.println("  9) Embedding API key:    (provider has no embeddings — uses in-process model)");
            }
            out.printf(" 10) Serper key (research): %s%n",
                    maskedFor(state.getSerperKey(), !state.isTenantCreated()));
            out.println();
            out.flush();

            String in = readLine(reader, "Edit [1-10], s) Save, q) Quit: ");
            if (in == null) {
                return false;
            }
            in = in.trim().toLowerCase();
            switch (in) {
                case "s" -> {
                    if (validate(out, state)) {
                        return true;
                    }
                }
                case "q" -> {
                    return false;
                }
                case "1" -> out.println("Tenant name is immutable. Restart the wizard to pick a different tenant.");
                case "2" -> {
                    String v = readLine(reader, "Tenant title: ");
                    if (v != null) state.setTenantTitle(v.trim());
                }
                case "3" -> out.println("User name is immutable. Restart the wizard to pick a different user.");
                case "4" -> {
                    String v = readLine(reader, "User title: ");
                    if (v != null) {
                        state.setUserTitle(v.trim());
                        if (!state.isUserCreated()) {
                            state.setUserFieldsChanged(true);
                        }
                    }
                }
                case "5" -> {
                    String v = readLine(reader, "User email: ");
                    if (v != null) {
                        state.setUserEmail(StringUtils.isBlank(v) ? null : v.trim());
                        if (!state.isUserCreated()) {
                            state.setUserFieldsChanged(true);
                        }
                    }
                }
                case "6" -> editProvider(out, reader, state);
                case "7" -> {
                    String v = readLine(reader, "AI model: ");
                    if (v != null && !v.isBlank()) state.setAiModel(v.trim());
                }
                case "8" -> {
                    String v = readPassword(reader, "AI API key (blank to keep current): ");
                    if (!StringUtils.isBlank(v)) state.setAiApiKey(v);
                }
                case "9" -> {
                    if (!state.getProvider().supportsEmbedding()) {
                        out.println("Provider " + state.getProvider().displayName()
                                + " has no embedding endpoint — skip.");
                        break;
                    }
                    String v = readPassword(reader,
                            "Embedding API key (blank to reuse chat key): ");
                    if (!StringUtils.isBlank(v)) state.setEmbeddingApiKey(v);
                }
                case "10" -> {
                    String v = readPassword(reader,
                            "Serper key (https://serper.dev, blank to skip research): ");
                    if (!StringUtils.isBlank(v)) state.setSerperKey(v);
                }
                default -> out.println("Unknown choice.");
            }
            out.flush();
        }
    }

    private void editProvider(PrintWriter out, LineReader reader, SetupState state) {
        out.println("AI providers:");
        ProviderPreset[] values = ProviderPreset.values();
        for (int i = 0; i < values.length; i++) {
            out.printf("  %d) %s%n", i + 1, values[i].displayName());
        }
        out.flush();
        String in = readLine(reader, "Pick provider: ");
        if (in == null) return;
        Integer pick = parsePositiveInt(in);
        if (pick == null || pick < 1 || pick > values.length) {
            out.println("Invalid choice.");
            out.flush();
            return;
        }
        ProviderPreset chosen = values[pick - 1];
        if (chosen != state.getProvider()) {
            state.setProvider(chosen);
            state.setAiModel(chosen.defaultModel());
            // Different provider ⇒ existing chat key is wrong for the new
            // one. Wipe so the menu's "blank means keep" rule doesn't keep
            // a stale key around.
            state.setAiApiKey(null);
            state.setEmbeddingApiKey(null);
        }
    }

    private boolean validate(PrintWriter out, SetupState state) {
        if (state.isTenantCreated() && StringUtils.isBlank(state.getAiApiKey())) {
            out.println("New tenant requires an AI API key (menu entry 8).");
            out.flush();
            return false;
        }
        return true;
    }

    // ──────────────────────── save ────────────────────────

    private void save(PrintWriter out, SetupState state) {
        out.println();
        out.println("Saving…");
        out.flush();

        if (state.isTenantCreated()) {
            tenantService.ensure(state.getTenantId(), state.getTenantTitle());
            out.println("  + tenant '" + state.getTenantId() + "' created");
        } else if (!StringUtils.equals(
                state.getTenantTitle(),
                tenantService.findByName(state.getTenantId())
                        .map(TenantDocument::getTitle).orElse(null))) {
            tenantService.update(state.getTenantId(), state.getTenantTitle(), null);
            out.println("  ~ tenant '" + state.getTenantId() + "' title updated");
        }

        // _tenant project must exist before SettingService writes land
        // somewhere coherent. ensureTenantProject is idempotent.
        homeBootstrapService.ensureTenantProject(state.getTenantId());

        if (state.isUserCreated()) {
            String hash = passwordService.hash(state.getUserPassword());
            userService.create(
                    state.getTenantId(),
                    state.getUserName(),
                    hash,
                    state.getUserTitle(),
                    state.getUserEmail());
            out.println("  + user '" + state.getUserName() + "' created");
        } else if (state.isUserFieldsChanged()) {
            userService.update(
                    state.getTenantId(),
                    state.getUserName(),
                    state.getUserTitle(),
                    state.getUserEmail(),
                    null,
                    null);
            out.println("  ~ user '" + state.getUserName() + "' updated");
        }

        writeProviderSettings(out, state);
        if (!StringUtils.isBlank(state.getSerperKey())) {
            writeResearchSettings(out, state);
        }

        out.println();
        out.println("Done.");
        out.flush();
    }

    private void writeProviderSettings(PrintWriter out, SetupState state) {
        String tenantId = state.getTenantId();
        ProviderPreset preset = state.getProvider();
        setString(tenantId, "ai.default.provider", preset.settingsId(),
                "Default AI provider for new sessions.");
        setString(tenantId, "ai.default.model", state.getAiModel(),
                "Default model id for the configured provider.");
        // Aliases all point at the chat model — operator can split later
        // through the Web-UI / settings if they want fast vs. analyze vs.
        // deep tiers on different models.
        String fqModel = preset.settingsId() + ":" + state.getAiModel();
        for (String alias : List.of("fast", "analyze", "deep", "web", "code")) {
            setString(tenantId, "ai.alias.default." + alias, fqModel, null);
        }
        if (!StringUtils.isBlank(state.getAiApiKey())) {
            settingService.setEncryptedPassword(
                    tenantId, SettingService.SCOPE_PROJECT,
                    HomeBootstrapService.TENANT_PROJECT_NAME,
                    "ai.provider." + preset.settingsId() + ".apiKey",
                    state.getAiApiKey());
            out.println("  + " + preset.displayName() + " API key written");
        }
        if (preset.supportsEmbedding()) {
            setString(tenantId, "ai.embedding.provider", preset.settingsId(),
                    "Embedding provider for RAG indexing.");
            String embedKey = StringUtils.isBlank(state.getEmbeddingApiKey())
                    ? state.getAiApiKey()
                    : state.getEmbeddingApiKey();
            if (!StringUtils.isBlank(embedKey)) {
                settingService.setEncryptedPassword(
                        tenantId, SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        "ai.embedding.apiKey", embedKey);
            }
        } else {
            // In-process model, no key. Matches the embedded provider in
            // ai.embedding.provider's documented vocabulary.
            setString(tenantId, "ai.embedding.provider", "embedded",
                    "Embedding provider for RAG indexing (in-process E5).");
        }
        out.println("  ~ AI defaults written (" + preset.displayName() + " / " + state.getAiModel() + ")");
    }

    /**
     * Writes the complete research-endpoint block from {@code init-settings.yaml}
     * when the operator supplied a Serper key. Keyless providers (wiki/hn/
     * openlib/openalex/arxiv) come along for the ride — they don't need a
     * credential and give the tenant a usable academic / news / encyclopaedia
     * stack out of the box.
     */
    private void writeResearchSettings(PrintWriter out, SetupState state) {
        String tenantId = state.getTenantId();

        // Serper (web search) — requires the operator-supplied key.
        setString(tenantId, "research.endpoint.serper-main.protocol", "serper", null);
        setString(tenantId, "research.endpoint.serper-main.baseUrl",
                "https://google.serper.dev", null);
        settingService.setEncryptedPassword(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                "research.endpoint.serper-main.apiKey",
                state.getSerperKey());
        setBoolean(tenantId, "research.endpoint.serper-main.enabled", true);
        setString(tenantId, "research.default.web", "serper-main", null);

        // Wikipedia (encyclopaedia) — keyless, doubles as web fallback.
        setString(tenantId, "research.endpoint.wiki-de.protocol", "wikipedia", null);
        setString(tenantId, "research.endpoint.wiki-de.baseUrl",
                "https://de.wikipedia.org/w/api.php", null);
        setBoolean(tenantId, "research.endpoint.wiki-de.enabled", true);
        setString(tenantId, "research.fallback.web", "wiki-de", null);
        setString(tenantId, "research.default.encyclopedia", "wiki-de", null);

        // HackerNews via Algolia — keyless, default news source.
        setString(tenantId, "research.endpoint.hn-algolia.protocol", "hackernews", null);
        setString(tenantId, "research.endpoint.hn-algolia.baseUrl",
                "https://hn.algolia.com/api/v1", null);
        setBoolean(tenantId, "research.endpoint.hn-algolia.enabled", true);
        setString(tenantId, "research.default.news", "hn-algolia", null);

        // OpenLibrary — keyless, book lookups.
        setString(tenantId, "research.endpoint.openlib.protocol", "openlibrary", null);
        setString(tenantId, "research.endpoint.openlib.baseUrl",
                "https://openlibrary.org", null);
        setBoolean(tenantId, "research.endpoint.openlib.enabled", true);
        setString(tenantId, "research.default.book", "openlib", null);

        // OpenAlex (academic, polite pool optional).
        setString(tenantId, "research.endpoint.openalex.protocol", "openalex", null);
        setString(tenantId, "research.endpoint.openalex.baseUrl",
                "https://api.openalex.org", null);
        setBoolean(tenantId, "research.endpoint.openalex.enabled", true);
        setString(tenantId, "research.default.academic", "openalex", null);

        // arXiv — academic fallback.
        setString(tenantId, "research.endpoint.arxiv.protocol", "arxiv", null);
        setString(tenantId, "research.endpoint.arxiv.baseUrl",
                "https://export.arxiv.org/api", null);
        setBoolean(tenantId, "research.endpoint.arxiv.enabled", true);
        setString(tenantId, "research.fallback.academic", "arxiv", null);

        out.println("  + Serper key written, research stack enabled");
    }

    private void setString(String tenantId, String key, String value, @Nullable String description) {
        settingService.set(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                key, value, SettingType.STRING, description);
    }

    private void setBoolean(String tenantId, String key, boolean value) {
        settingService.set(
                tenantId, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                key, Boolean.toString(value), SettingType.BOOLEAN, null);
    }

    // ──────────────────────── helpers ────────────────────────

    private static @Nullable String readLine(LineReader reader, String prompt) {
        try {
            String s = reader.readLine(prompt);
            return s == null ? null : s;
        } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
            return null;
        }
    }

    private static String readPassword(LineReader reader, String prompt) {
        try {
            String s = reader.readLine(prompt, '*');
            return s == null ? "" : s;
        } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
            return "";
        }
    }

    private static @Nullable Integer parsePositiveInt(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Renders a key field in the menu. {@code hasExisting=true} for existing
     * tenants where an unedited blank field means "keep what's already in
     * the database" — we say "(keep existing)" rather than "—" so the operator
     * isn't confused into thinking the slot is empty.
     */
    private static String maskedFor(@Nullable String value, boolean hasExisting) {
        if (value == null || value.isEmpty()) {
            return hasExisting ? "(keep existing)" : "(not set)";
        }
        int len = Math.min(value.length(), 8);
        return "*".repeat(len);
    }

    /**
     * Test hook — exposes the (otherwise private) overview rendering so a
     * unit test can verify the format without booting the whole wizard.
     */
    void renderOverviewForTest(PrintWriter out) {
        printOverview(out, listOperatorTenants());
    }
}
