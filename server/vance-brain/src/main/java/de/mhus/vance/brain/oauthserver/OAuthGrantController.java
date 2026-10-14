package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.api.oauthserver.OAuthGrantDto;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Connected apps": the caller's own OAuth grants, and revoking one.
 *
 * <p>Needs a full-account login — a narrowed credential must not list or
 * revoke grants. Someone else's grant id is 404, not 403, so the answer does
 * not confirm that the id exists.
 */
@RestController
@RequiredArgsConstructor
public class OAuthGrantController {

    private final OAuthServerGrantService grantService;

    @GetMapping("/brain/{tenant}/oauth/grants")
    public List<OAuthGrantDto> list(@PathVariable("tenant") String tenant, HttpServletRequest request) {
        String user = requireUser(request);
        Instant now = Instant.now();
        return grantService.list(tenant, user).stream().map(g -> toDto(g, now)).toList();
    }

    @DeleteMapping("/brain/{tenant}/oauth/grants/{grantId}")
    public ResponseEntity<Void> revoke(
            @PathVariable("tenant") String tenant,
            @PathVariable("grantId") String grantId,
            HttpServletRequest request) {
        String user = requireUser(request);
        OAuthServerGrantDocument grant = grantService
                .find(tenant, grantId)
                .filter(g -> user.equals(g.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Audited and announced by the grant service, like every revocation.
        grantService.revoke(tenant, grant.getGrantId(), "user");
        return ResponseEntity.noContent().build();
    }

    private static String requireUser(HttpServletRequest request) {
        String user = OAuthAuthorizeController.loggedInUser(request);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A login session is required.");
        }
        return user;
    }

    static OAuthGrantDto toDto(OAuthServerGrantDocument g, Instant now) {
        boolean active = g.getRevokedAt() == null
                && (g.getAbsoluteExpiresAt() == null || g.getAbsoluteExpiresAt().isAfter(now));
        return OAuthGrantDto.builder()
                .grantId(g.getGrantId())
                .clientId(g.getClientId())
                .clientName(g.getClientName())
                .scope(g.getScope())
                .resource(g.getResource())
                .projectId(g.getProjectId())
                .active(active)
                .createdAtTimestamp(millis(g.getCreatedAt()))
                .lastUsedAtTimestamp(millis(g.getLastUsedAt()))
                .expiresAtTimestamp(millis(g.getAbsoluteExpiresAt()))
                .revokedAtTimestamp(millis(g.getRevokedAt()))
                .revokeReason(g.getRevokeReason())
                .build();
    }

    private static @Nullable Long millis(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
