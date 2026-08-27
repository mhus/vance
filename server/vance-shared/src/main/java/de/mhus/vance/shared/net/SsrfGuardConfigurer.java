package de.mhus.vance.shared.net;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wires the {@link SsrfGuard} dev/test escape hatch from configuration. The
 * guard itself is a static utility (reached from many call sites without DI),
 * so this boot-time component pushes the operator-configured flag into it.
 *
 * <p>{@code vance.net.ssrf.allow-private} defaults to {@code false} — production
 * must keep it that way. Set it to {@code true} ONLY in a local/test profile to
 * let {@code web_fetch}/{@code doc_import_url} reach {@code localhost} or a LAN
 * service; it re-opens SSRF and is logged loudly on startup. It is deliberately
 * an operator config (application.yml, profile-scoped), never an
 * LLM-controllable per-call flag such as {@code insecure} (which only relaxes
 * TLS verification, not the address policy).
 */
@Component
@Slf4j
public class SsrfGuardConfigurer {

    private final boolean allowPrivate;

    public SsrfGuardConfigurer(
            @Value("${vance.net.ssrf.allow-private:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    @PostConstruct
    void apply() {
        SsrfGuard.setAllowPrivate(allowPrivate);
        if (allowPrivate) {
            log.warn("SSRF egress guard: vance.net.ssrf.allow-private=true — "
                    + "loopback/private/link-local targets are ALLOWED. This is a "
                    + "development/testing setting and re-opens SSRF; never enable it "
                    + "in production.");
        }
    }
}
