package de.mhus.vance.brain.sso;

import de.mhus.vance.brain.documents.events.RoutedDocumentChangedEvent;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.sso.SsoConfigService;
import de.mhus.vance.shared.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Evicts the SSO config cache when {@code _vance/config/sso.yaml} changes — on every pod. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SsoConfigCacheListener {

    private final SsoConfigService configService;

    @EventListener
    public void onRoutedDocumentChanged(RoutedDocumentChangedEvent event) {
        if (SsoConfigService.CONFIG_PATH.equals(event.path())
                && TenantService.SYSTEM_TENANT.equals(event.tenantId())
                && HomeBootstrapService.TENANT_PROJECT_NAME.equals(event.projectId())) {
            configService.invalidate();
            log.debug("SSO config changed, cache evicted");
        }
    }
}
