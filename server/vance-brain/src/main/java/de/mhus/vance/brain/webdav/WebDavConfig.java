package de.mhus.vance.brain.webdav;

import de.mhus.vance.brain.webdav.lock.DavLockManager;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserService;
import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.LockManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the WebDAV surface (DAV level 2). Lock state and the macOS sidecar cache live on the
 * messaging service's TTL values, so they span pods in the cloud edition (Redis) and stay in the
 * process on a single node. Switched off with {@code vance.webdav.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(prefix = "vance.webdav", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WebDavProperties.class)
@Slf4j
public class WebDavConfig {

    @Bean
    public SidecarStore webdavSidecarStore(VanceRedisMessagingService messaging, WebDavProperties properties) {
        return new SidecarStore(messaging, properties);
    }

    @Bean
    public VanceWebDavSecurityManager webdavSecurityManager(
            PasswordService passwordService,
            UserService userService,
            TeamService teamService,
            PermissionService permissionService,
            WebDavProperties properties) {
        return new VanceWebDavSecurityManager(passwordService, userService, teamService, permissionService, properties);
    }

    @Bean
    public LockManager webdavLockManager(VanceRedisMessagingService messaging, WebDavProperties properties) {
        return new DavLockManager(messaging, properties);
    }

    @Bean
    public DocumentResourceFactory webdavResourceFactory(
            DocumentService documentService,
            VanceWebDavSecurityManager securityManager,
            SidecarStore sidecarStore,
            LockManager webdavLockManager,
            WebDavProperties properties) {
        return new DocumentResourceFactory(
                documentService, securityManager, sidecarStore, webdavLockManager, properties);
    }

    @Bean
    public WebDavLockService webdavLockService(DocumentResourceFactory webdavResourceFactory) {
        return new WebDavLockService(webdavResourceFactory);
    }

    @Bean
    public HttpManager webdavHttpManager(
            DocumentResourceFactory resourceFactory, VanceWebDavSecurityManager securityManager) {
        HttpManagerBuilder builder = new HttpManagerBuilder();
        builder.setResourceFactory(resourceFactory);
        builder.setSecurityManager(securityManager);
        // Basic-Auth only; no HTML form login on this surface.
        builder.setEnableBasicAuth(true);
        builder.setEnableFormAuth(false);
        HttpManager manager = builder.buildHttpManager();
        log.info("WebDAV surface enabled at /brain/{{tenant}}/webdav/ (DAV level 2)");
        return manager;
    }

    @Bean
    public FilterRegistrationBean<WebDavFilter> webdavFilterRegistration(
            HttpManager webdavHttpManager, WebDavLockService webdavLockService) {
        FilterRegistrationBean<WebDavFilter> registration =
                new FilterRegistrationBean<>(new WebDavFilter(webdavHttpManager, webdavLockService));
        registration.addUrlPatterns("/brain/*");
        registration.setName("webdavFilter");
        // Run before BrainAccessFilter so WebDAV requests never hit the JWT
        // gate; non-WebDAV /brain requests fall straight through.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
