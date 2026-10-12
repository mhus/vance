package de.mhus.vance.brain.ai.decision.systemone;

import de.mhus.vance.brain.ai.ProviderType;
import org.springframework.stereotype.Component;

/**
 * Instances of protocol {@code systemone} — endpoints that speak only
 * decisions: TypeSafe direct (default base-URL), Upstage, Venice's alias
 * route, a local {@code laya-serve}. Not live-verified (no key at the
 * time of writing); the wire is the same one OpenRouter passes through.
 *
 * <p>The key is sent when configured but not enforced: a local
 * {@code laya-serve} runs without one, and a hosted endpoint without a
 * key answers 401, which surfaces as {@code AUTH} with the endpoint's
 * own message.
 */
@Component
public class DirectSystemOneProvider extends SystemOneProviderSupport {

    private static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";

    public DirectSystemOneProvider(SystemOneWireClient wire) {
        super(wire, DEFAULT_BASE_URL, false);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.SYSTEMONE;
    }
}
