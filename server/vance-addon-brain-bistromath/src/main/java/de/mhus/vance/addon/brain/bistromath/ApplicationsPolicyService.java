package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads {@code _vance/config/applications.yaml} from the tenant project and
 * answers, for one app, what it may do.
 *
 * <p><b>The server resolves, the client enforces.</b> Not a contradiction: the
 * enforcement is complete in the client, because a guest can issue HTTP but
 * never <em>authenticated</em> HTTP — every call that arrives went through the
 * host. The resolution is here for a different reason: so the tenant's rule set
 * does not travel to every browser, readable by every project member. The client
 * receives one answer about itself and nothing about anybody else.
 *
 * <p><b>No cache, deliberately.</b> The read happens when an app is opened or
 * rebuilt, not per request — one extra document read on a path a person just
 * clicked. A cache would buy nothing measurable and would add an invalidation
 * path that can be wrong, which for a policy means being wrong in the
 * permissive direction for as long as nobody notices.
 *
 * <p><b>Malformed fails closed.</b> Same reasoning as {@code KitSourceRegistry}
 * makes for kit sources: "no rules" is not the narrower reading of a broken
 * file, it is the widest one. A typo in one line must not turn a tenant's
 * {@code forbidden} into an unrestricted one, so a file that does not parse
 * refuses every app until it is fixed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationsPolicyService {

    private final DocumentService documentService;
    private final AppGrantStore grants;

    /**
     * The effective policy for one app folder, across both documents.
     *
     * <p>Order: what the hand-written file says **about this app**, then a
     * granted release, then the project or global default. The admin's own file
     * wins wherever it names the app — so revoking is naming it, not hunting
     * through the machine-written grants.
     */
    public AppPolicy resolve(String tenantId, String projectId, String appFolder) {
        ApplicationsConfig config = config(tenantId);
        AppPolicy explicit = config.explicitAppRule(projectId, appFolder);
        if (explicit != null) return explicit;

        AppGrantRecord record = grants.find(
                tenantId, ApplicationsConfig.appKey(projectId, appFolder));
        AppPolicy granted = record == null ? null : record.grantedPolicy();
        if (granted != null) return granted;

        return config.projectOrGlobal(projectId);
    }

    /** The hand-written configuration, for the request path. */
    public ApplicationsConfig configuration(String tenantId) {
        return config(tenantId);
    }

    private ApplicationsConfig config(String tenantId) {
        Optional<DocumentDocument> doc = documentService.findByPath(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, ApplicationsConfig.PATH);
        if (doc.isEmpty()) return ApplicationsConfig.missing();
        String text = readText(doc.get());
        if (text == null || text.isBlank()) return ApplicationsConfig.missing();
        try {
            return ApplicationsConfig.parse(new Yaml().load(text));
        } catch (ToolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolException(ApplicationsConfig.PATH + " in tenant '" + tenantId
                    + "' is malformed and cannot be trusted to say which applications are"
                    + " allowed — fix it before opening an app (" + e.getMessage() + ")", e);
        }
    }

    private @Nullable String readText(DocumentDocument doc) {
        String inline = documentService.readContent(doc);
        if (inline != null) return inline;
        try (var in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Not "treat as absent": absent means forbidden, and a read error is
            // not evidence of anything. Throwing keeps the two apart.
            throw new ToolException("Failed to read " + ApplicationsConfig.PATH
                    + " — refusing to guess what it says.", e);
        }
    }
}
