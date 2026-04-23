package de.mhus.vance.brain.access;

import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.JwtService;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Access filter for the Brain.
 *
 * <p>Open paths:
 * <ul>
 *   <li>{@code /actuator/**} — health / info probes</li>
 *   <li>{@code /brain/{tenant}/access/{username}} — token-mint endpoint served by
 *       {@link AccessController}. Must be open because the client has no token yet
 *       when calling it.</li>
 * </ul>
 * Everything else — including {@code /brain/ws} — needs a valid bearer JWT.
 */
@Component
@Slf4j
public class BrainAccessFilter extends AccessFilterBase {

    private static final Pattern TOKEN_MINT_PATH = Pattern.compile("^/brain/[^/]+/access/[^/]+/?$");

    public BrainAccessFilter(JwtService jwtService) {
        super(jwtService);
    }

    @Override
    protected boolean shouldRequireAuthentication(String requestUri, String method) {
        if (requestUri.startsWith("/actuator/")) {
            return false;
        }
        if (TOKEN_MINT_PATH.matcher(requestUri).matches()) {
            return false;
        }
        return true;
    }
}
