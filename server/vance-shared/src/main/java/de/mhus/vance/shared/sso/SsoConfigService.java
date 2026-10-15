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
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Serves the installation-wide {@code _vance/config/sso.yaml}.
 *
 * <p>The file lives in the system tenant ({@code _vance}, project
 * {@code _tenant}) and nowhere else: at sign-in the tenant is not known yet —
 * the e-mail domain names it — and which domain belongs to which tenant is the
 * operator's decision. A tenant admin who could write the mapping could route
 * another company's sign-ins into their own tenant.
 *
 * <p>Cached briefly. In the Brain a change to the document evicts the cache on
 * every pod ({@link #invalidate()}, called by the document-change listener);
 * the admin shell simply re-reads after the TTL.
 */
@Service
@Slf4j
public class SsoConfigService {

    public static final String CONFIG_PATH = "_vance/config/sso.yaml";

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final DocumentService documentService;
    private final TenantService tenantService;
    private final Clock clock;

    private volatile @Nullable Cached cached;

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

    public SsoConfig config() {
        Instant now = clock.instant();
        Cached c = cached;
        if (c != null && now.isBefore(c.expiresAt())) {
            return c.config();
        }
        SsoConfig parsed = readAndParse();
        cached = new Cached(parsed, now.plus(CACHE_TTL));
        return parsed;
    }

    /** Drops the cached config; the next {@link #config()} reads the document again. */
    public void invalidate() {
        cached = null;
    }

    /** The document's text as stored, if there is one — for the admin shell. */
    public Optional<String> readRaw() {
        return documentService
                .findByPath(TenantService.SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, CONFIG_PATH)
                .map(documentService::readContent);
    }

    /**
     * What {@code yaml} would mean, without writing it. Every entry that would be
     * dropped is added to {@code problems}.
     */
    public SsoConfig validate(String yaml, List<String> problems) {
        return SsoConfigParser.parse(yaml, this::tenantUsable, problems);
    }

    /**
     * Replaces the config document. Operator path only (admin shell) — the
     * document lives in the system tenant, and writing it decides which
     * domain's sign-ins land in which tenant.
     */
    public void write(String yaml, String writtenBy) {
        documentService.upsertText(
                TenantService.SYSTEM_TENANT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                CONFIG_PATH,
                "SSO login",
                null,
                yaml,
                writtenBy,
                WriteActor.SYSTEM);
        invalidate();
        log.info("SSO config written by '{}'", writtenBy);
    }

    private SsoConfig readAndParse() {
        String content;
        try {
            Optional<DocumentDocument> doc = documentService.findByPath(
                    TenantService.SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, CONFIG_PATH);
            if (doc.isEmpty()) {
                return SsoConfig.EMPTY;
            }
            content = documentService.readContent(doc.get());
        } catch (RuntimeException ex) {
            log.error("SSO config: reading {} failed — SSO disabled: {}", CONFIG_PATH, ex.toString());
            return SsoConfig.EMPTY;
        }
        return SsoConfigParser.parse(content, this::tenantUsable);
    }

    private boolean tenantUsable(String name) {
        return tenantService.findByName(name).map(TenantDocument::isEnabled).orElse(false);
    }
}
