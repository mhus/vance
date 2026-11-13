package de.mhus.vance.brain.sso;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.sso.SsoConfigService;
import de.mhus.vance.shared.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Evicts the cached SSO config of a tenant when its {@code _vance/config/sso/<tenant>.yaml} changes — on every pod. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SsoConfigCacheListener {

    private final SsoConfigService configService;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        if (!TenantService.SYSTEM_TENANT.equals(event.tenantId())
                || !HomeBootstrapService.TENANT_PROJECT_NAME.equals(event.projectId())) {
            return;
        }
        SsoConfigService.tenantOfPath(event.path()).ifPresent(tenant -> {
            configService.invalidate(tenant);
            log.debug("SSO config of '{}' changed, cache evicted", tenant);
        });
    }
}
