package de.mhus.vance.shared.net;

import jakarta.annotation.PostConstruct;
import java.util.List;
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
 *
 * <p>Production policy: {@code vance.net.ssrf.private-networks} ({@code deny}
 * by default, {@code allow} to let tenants reach private ranges) plus
 * {@code vance.net.ssrf.denied}, the CIDR ranges and hosts no tenant may reach
 * ({@link EgressDenyList}). Both operator config only — a tenant cannot change
 * them.
 */
@Component
@Slf4j
public class SsrfGuardConfigurer {

    private final boolean allowPrivate;
    private final String privateNetworks;
    private final List<String> denied;
    private final List<String> providerHosts;

    public SsrfGuardConfigurer(
            @Value("${vance.net.ssrf.allow-private:false}") boolean allowPrivate,
            @Value("${vance.net.ssrf.private-networks:deny}") String privateNetworks,
            @Value("${vance.net.ssrf.denied:}") List<String> denied,
            @Value("${vance.net.ssrf.provider-hosts:}") List<String> providerHosts) {
        this.allowPrivate = allowPrivate;
        this.privateNetworks = privateNetworks;
        this.denied = denied;
        this.providerHosts = providerHosts;
    }

    @PostConstruct
    void apply() {
        SsrfGuard.setAllowPrivate(allowPrivate);
        SsrfGuard.PrivateNetworks mode = SsrfGuard.PrivateNetworks.parse(privateNetworks);
        SsrfGuard.setPrivateNetworks(mode);
        EgressDenyList denyList = EgressDenyList.parse(denied);
        SsrfGuard.setDenyList(denyList);
        SsrfGuard.setProviderHosts(providerHosts);
        log.info("SSRF egress guard: private-networks={}, denied={}, provider-hosts={}", mode, denied, providerHosts);
        if (mode == SsrfGuard.PrivateNetworks.ALLOW && denyList.isEmpty()) {
            log.warn("SSRF egress guard: private networks are reachable and vance.net.ssrf.denied is empty — "
                    + "tenants can reach every internal service of this network (databases, cluster API)");
        }
        if (allowPrivate) {
            log.warn("SSRF egress guard: vance.net.ssrf.allow-private=true — "
                    + "loopback/private/link-local targets are ALLOWED. This is a "
                    + "development/testing setting and re-opens SSRF; never enable it "
                    + "in production.");
        }
    }
}
