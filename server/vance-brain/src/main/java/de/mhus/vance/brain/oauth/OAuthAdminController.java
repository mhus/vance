package de.mhus.vance.brain.oauth;

import de.mhus.vance.api.oauth.OAuthProviderAdminDto;
import de.mhus.vance.api.oauth.OAuthProviderWriteRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-admin surface for managing OAuth provider configurations.
 * Distinct from the user-facing {@code OAuthController} — admins
 * configure <i>which</i> providers the tenant supports; users connect
 * <i>their own accounts</i> to those providers.
 *
 * <p>Provider configs live as {@link DocumentService} documents at
 * {@code oauth/<providerId>.yaml} inside the {@code _tenant} system
 * project. The client secret is stored separately as a tenant
 * PASSWORD setting at {@code oauth.<providerId>.client_secret} — never
 * in the YAML body, and never visible in the admin DTO.
 *
 * <p>Permission: {@code Action.ADMIN} on {@link Resource.Tenant} — only
 * tenant admins can read or write provider configs.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/oauth")
@RequiredArgsConstructor
@Slf4j
public class OAuthAdminController {

    private final OAuthProviderLoader loader;
    private final OAuthConfigRegistry configRegistry;
    private final DocumentService documentService;
    private final SettingService settingService;
    private final RequestAuthority authority;

    /**
     * Actor for this admin surface: a deliberate write of a {@code _vance/oauth/}
     * system resource on behalf of the request user. The surface has already run
     * its Tenant ADMIN check; WriteReason.SYSTEM is the hint that lets the
     * resolver allow the reserved-path write, real user kept for audit. (F1)
     */
    private de.mhus.vance.shared.permission.WriteActor systemActor(
            jakarta.servlet.http.HttpServletRequest request) {
        return de.mhus.vance.shared.permission.WriteActor.system(authority.contextOf(request));
    }

    // ──────────────────── List ────────────────────

    @GetMapping("/providers")
    public List<OAuthProviderAdminDto> listProviders(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        List<OAuthProviderAdminDto> out = new ArrayList<>();
        for (ResolvedOAuthProvider rp : configRegistry.list(tenant)) {
            out.add(toDto(tenant, rp.config().providerId(), rp.config()));
        }
        out.sort(Comparator.comparing(OAuthProviderAdminDto::getProviderId));
        return out;
    }

    @GetMapping("/providers/{providerId}")
    public OAuthProviderAdminDto getProvider(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        ResolvedOAuthProvider rp = configRegistry.resolve(tenant, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No OAuth provider '" + providerId + "' in tenant '" + tenant + "'"));
        return toDto(tenant, rp.config().providerId(), rp.config());
    }

    // ──────────────────── Upsert ────────────────────

    @PutMapping("/providers/{providerId}")
    public ResponseEntity<OAuthProviderAdminDto> upsertProvider(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            @Valid @RequestBody OAuthProviderWriteRequest body,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        String norm = OAuthProviderLoader.normalizedName(providerId);
        String createdBy = authority.contextOf(httpRequest).subjectId();

        if (body.getYaml() == null || body.getYaml().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'yaml' must be a non-empty string");
        }
        try {
            loader.validateYaml(norm, body.getYaml());
        } catch (OAuthProviderLoader.OAuthProviderParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        String path = OAuthProviderLoader.pathFor(norm);
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenant, HomeBootstrapService.TENANT_PROJECT_NAME, path);
        boolean created = existing.isEmpty();
        if (existing.isPresent()) {
            documentService.update(existing.get().getId(), null, null, body.getYaml(), null,
                    systemActor(httpRequest));
        } else {
            documentService.createText(
                    tenant, HomeBootstrapService.TENANT_PROJECT_NAME, path,
                    "OAuth provider: " + norm, null, body.getYaml(), createdBy,
                    systemActor(httpRequest));
        }

        // clientSecret handling: null = leave alone; empty = explicit
        // remove; non-empty = upsert. Mirrors the OAuthProviderWriteRequest
        // contract documented on the DTO.
        if (body.getClientSecret() != null) {
            String secretKey = OAuthProviderLoader.clientSecretKey(norm);
            if (body.getClientSecret().isEmpty()) {
                settingService.delete(tenant, SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME, secretKey);
            } else {
                settingService.setEncryptedPassword(tenant, SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME, secretKey,
                        body.getClientSecret());
            }
        }

        configRegistry.refreshOne(tenant, norm);
        log.info("OAuth provider admin {}: tenant='{}' provider='{}'",
                created ? "created" : "updated", tenant, norm);

        OAuthProviderAdminDto dto = configRegistry.resolve(tenant, norm)
                .map(rp -> toDto(tenant, norm, rp.config()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "OAuth provider vanished immediately after write"));
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(dto);
    }

    // ──────────────────── Delete ────────────────────

    @DeleteMapping("/providers/{providerId}")
    public ResponseEntity<Void> deleteProvider(
            @PathVariable("tenant") String tenant,
            @PathVariable("providerId") String providerId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        String norm = OAuthProviderLoader.normalizedName(providerId);

        Optional<DocumentDocument> existing = documentService.findByPath(
                tenant, HomeBootstrapService.TENANT_PROJECT_NAME,
                OAuthProviderLoader.pathFor(norm));
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        documentService.delete(existing.get().getId(), systemActor(httpRequest));
        settingService.delete(tenant, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                OAuthProviderLoader.clientSecretKey(norm));
        configRegistry.refreshOne(tenant, norm);
        log.info("OAuth provider admin deleted: tenant='{}' provider='{}'", tenant, norm);
        return ResponseEntity.noContent().build();
    }

    // ──────────────────── Mapping ────────────────────

    private OAuthProviderAdminDto toDto(
            String tenant, String providerId, OAuthProviderConfig cfg) {
        // Re-read the YAML body straight from DocumentService so the
        // editor receives the verbatim text (with comments, ordering).
        // For resource-layer entries the body still round-trips.
        String yamlBody = documentService.lookupCascade(
                        tenant, HomeBootstrapService.TENANT_PROJECT_NAME,
                        OAuthProviderLoader.pathFor(providerId))
                .map(hit -> hit.content())
                .orElse(null);
        boolean hasSecret = settingService.getDecryptedPassword(
                tenant, SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                OAuthProviderLoader.clientSecretKey(providerId)) != null;
        return OAuthProviderAdminDto.builder()
                .providerId(providerId)
                .typeId(cfg.typeId())
                .clientId(cfg.clientId())
                .hasClientSecret(hasSecret)
                .yaml(yamlBody)
                .build();
    }
}
