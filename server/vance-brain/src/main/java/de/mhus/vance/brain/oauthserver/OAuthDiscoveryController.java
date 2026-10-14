package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.shared.tenant.TenantService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth discovery documents: the authorization server metadata (RFC 8414)
 * per tenant issuer, and the protected resource metadata (RFC 9728) per MCP
 * door.
 *
 * <p>The issuer {@code {base}/brain/{tenant}} has a path, so a client looks
 * for its metadata in three places, in this order (MCP authorization spec):
 * {@code /.well-known/oauth-authorization-server/brain/{tenant}},
 * {@code /.well-known/openid-configuration/brain/{tenant}},
 * {@code /brain/{tenant}/.well-known/openid-configuration}. All three serve
 * the same document. The first two live at the host root and need the
 * gateway to route {@code /.well-known/…} to the brain; the third works
 * through the ordinary {@code /brain} route, so discovery does not depend on
 * that gateway change.
 *
 * <p>Vance is no OpenID provider (no ID tokens); serving the
 * {@code openid-configuration} name is a discovery convenience, and the
 * document says only what is true of the OAuth server.
 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OAuthDiscoveryController {

    private final OAuthServerSettings settings;
    private final OAuthDoors doors;
    private final TenantService tenantService;

    @GetMapping(
            value = {
                "/.well-known/oauth-authorization-server/brain/{tenant}",
                "/.well-known/openid-configuration/brain/{tenant}",
                "/brain/{tenant}/.well-known/openid-configuration",
                "/brain/{tenant}/.well-known/oauth-authorization-server"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> authorizationServerMetadata(@PathVariable("tenant") String tenant) {
        if (!available(tenant)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String issuer = settings.issuer(tenant);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", issuer + "/oauth/authorize");
        doc.put("token_endpoint", issuer + "/oauth/token");
        if (settings.registrationEnabled()) {
            doc.put("registration_endpoint", issuer + "/oauth/register");
        }
        doc.put("revocation_endpoint", issuer + "/oauth/revoke");
        doc.put("response_types_supported", List.of("code"));
        doc.put("response_modes_supported", List.of("query"));
        doc.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        doc.put("token_endpoint_auth_methods_supported", List.of("none"));
        doc.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        doc.put(
                "scopes_supported",
                List.of(OAuthDoor.Kind.MCP.scope(), OAuthDoor.Kind.MCP_PROJECT.scope(), OAuthDoor.Kind.AGENTS.scope()));
        doc.put("client_id_metadata_document_supported", settings.clientMetadataDocumentsEnabled());
        doc.put("authorization_response_iss_parameter_supported", true);
        return ok(doc);
    }

    @GetMapping(
            value = {"/brain/{tenant}/oauth/resource/mcp", "/.well-known/oauth-protected-resource/brain/{tenant}/mcp"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> mcpResource(@PathVariable("tenant") String tenant) {
        return resourceMetadata(tenant, OAuthDoor.mcp());
    }

    @GetMapping(
            value = {
                "/brain/{tenant}/oauth/resource/mcp/{project}",
                "/.well-known/oauth-protected-resource/brain/{tenant}/mcp/{project}"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> mcpProjectResource(
            @PathVariable("tenant") String tenant, @PathVariable("project") String project) {
        return resourceMetadata(tenant, OAuthDoor.mcpProject(project));
    }

    @GetMapping(
            value = {
                "/brain/{tenant}/oauth/resource/agents",
                "/.well-known/oauth-protected-resource/brain/{tenant}/agents"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> agentsResource(@PathVariable("tenant") String tenant) {
        return resourceMetadata(tenant, OAuthDoor.agents());
    }

    private ResponseEntity<Map<String, Object>> resourceMetadata(String tenant, OAuthDoor door) {
        if (!available(tenant)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("resource", doors.resourceUrl(tenant, door));
        doc.put("authorization_servers", List.of(settings.issuer(tenant)));
        doc.put("scopes_supported", List.of(door.scope()));
        doc.put("bearer_methods_supported", List.of("header"));
        doc.put("resource_name", "Vancetope " + door.scope() + " (" + tenant + ")");
        return ok(doc);
    }

    private boolean available(String tenant) {
        return settings.enabled() && tenantService.findByName(tenant).isPresent();
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> doc) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
                .body(doc);
    }
}
