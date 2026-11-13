package de.mhus.vance.shared.sso;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Serves the SSO configuration of a tenant: {@code _vance/config/sso/<tenant>.yaml}.
 *
 * <p>The files live in the system tenant ({@code _vance}, project
 * {@code _tenant}) and nowhere else, one per tenant, and are read on demand —
 * the sign-in names its tenant before anything is looked up. Only the operator
 * writes into {@code _vance}: a tenant admin who could write the file could
 * point their tenant at another company's domain.
 *
 * <p>Cached briefly per tenant, and only for tenants that exist and are
 * enabled (so the cache is bounded by the number of tenants, whatever an
 * anonymous caller asks for). In the Brain a change to a file evicts its entry
 * on every pod ({@link #invalidate(String)}, called by the document-change
 * listener); the admin shell simply re-reads after the TTL.
 */
@Service
@Slf4j
public class SsoConfigService {

    /** Folder of the per-tenant files inside the system tenant's {@code _tenant} project. */
    public static final String CONFIG_DIR = "_vance/config/sso/";

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** A tenant name that is safe as a file name — the file is named after the tenant. */
    private static final Pattern TENANT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]*$");

    private final DocumentService documentService;
    private final TenantService tenantService;
    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(SsoConfig config, Instant expiresAt) {}

    @Autowired
    public SsoConfigService(DocumentService documentService, TenantService tenantService) {
        this(documentService, tenantService, Clock.systemUTC());
    }

    SsoConfigService(DocumentService documentService, TenantService tenantService, Clock clock) {
        this.documentService = documentService;
        this.tenantService = tenantService;
        this.clock = clock;
    }

    /** The path of {@code tenant}'s file inside the system tenant's {@code _tenant} project. */
    public static String configPath(String tenant) {
        return CONFIG_DIR + tenant + ".yaml";
    }

    /**
     * The tenant a changed document belongs to, if {@code path} is one of the
     * per-tenant files.
     */
    public static Optional<String> tenantOfPath(String path) {
        if (!path.startsWith(CONFIG_DIR) || !path.endsWith(".yaml")) {
            return Optional.empty();
        }
        String name = path.substring(CONFIG_DIR.length(), path.length() - ".yaml".length());
        return TENANT_NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }

    /**
     * The SSO configuration of {@code tenant} — empty ({@link SsoConfig#empty}) when
     * the tenant has none, does not exist, is disabled, or is the system tenant.
     */
    public SsoConfig config(String tenant) {
        if (!tenantUsable(tenant)) {
            cache.remove(tenant);
            return SsoConfig.empty(tenant);
        }
        Instant now = clock.instant();
        Cached c = cache.get(tenant);
        if (c != null && now.isBefore(c.expiresAt())) {
            return c.config();
        }
        SsoConfig parsed = readAndParse(tenant);
        cache.put(tenant, new Cached(parsed, now.plus(CACHE_TTL)));
        return parsed;
    }

    /** Drops the cached config of {@code tenant}; the next {@link #config} reads the document again. */
    public void invalidate(String tenant) {
        cache.remove(tenant);
    }

    /** The document's text as stored, if there is one — for the admin shell. */
    public Optional<String> readRaw(String tenant) {
        if (!TENANT_NAME.matcher(tenant).matches()) {
            return Optional.empty();
        }
        return documentService
                .findByPath(TenantService.SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, configPath(tenant))
                .map(documentService::readContent);
    }

    /**
     * What {@code yaml} would mean for {@code tenant}, without writing it. Every
     * entry that would be dropped is added to {@code problems}.
     */
    public SsoConfig validate(String tenant, String yaml, List<String> problems) {
        return SsoConfigParser.parse(tenant, yaml, problems);
    }

    /**
     * Replaces the config document of {@code tenant}. Operator path only (admin
     * shell) — the file lives in the system tenant.
     *
     * @throws IllegalArgumentException if {@code tenant} is not an existing, enabled tenant
     */
    public void write(String tenant, String yaml, String writtenBy) {
        if (!tenantUsable(tenant)) {
            throw new IllegalArgumentException(
                    "Tenant '" + tenant + "' does not exist, is disabled, or is not a sign-in target");
        }
        documentService.upsertText(
                TenantService.SYSTEM_TENANT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                configPath(tenant),
                "SSO login of " + tenant,
                null,
                yaml,
                writtenBy,
                WriteActor.SYSTEM);
        invalidate(tenant);
        log.info("SSO config of tenant '{}' written by '{}'", tenant, writtenBy);
    }

    private SsoConfig readAndParse(String tenant) {
        String content;
        try {
            Optional<DocumentDocument> doc = documentService.findByPath(
                    TenantService.SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, configPath(tenant));
            if (doc.isEmpty()) {
                return SsoConfig.empty(tenant);
            }
            content = documentService.readContent(doc.get());
        } catch (RuntimeException ex) {
            log.error(
                    "SSO config: reading {} failed — SSO of '{}' disabled: {}",
                    configPath(tenant),
                    tenant,
                    ex.toString());
            return SsoConfig.empty(tenant);
        }
        return SsoConfigParser.parse(tenant, content);
    }

    /** An existing, enabled tenant that is not the system tenant (which holds the files). */
    private boolean tenantUsable(@Nullable String tenant) {
        if (tenant == null || !TENANT_NAME.matcher(tenant).matches() || TenantService.SYSTEM_TENANT.equals(tenant)) {
            return false;
        }
        return tenantService.findByName(tenant).map(TenantDocument::isEnabled).orElse(false);
    }
}
