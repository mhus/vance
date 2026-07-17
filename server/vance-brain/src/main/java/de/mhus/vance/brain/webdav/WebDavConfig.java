package de.mhus.vance.brain.webdav;

import de.mhus.vance.brain.webdav.lock.RedisLockManager;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserService;
import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.LockManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the WebDAV surface. Gated on {@code vance.redis.enabled=true}: WebDAV
 * lock state lives in Redis (cross-pod), so without Redis the feature is simply
 * not registered — Brain still boots, WebDAV is unavailable (a deliberate hard
 * requirement, see {@code planning/webdav-support.md} §5). The lock protocol
 * itself (DAV level 2) is added in a follow-up step; this config is DAV level 1
 * (read/write), which already serves Obsidian and read-only Finder mounts.
 */
@Configuration
@ConditionalOnProperty(prefix = "vance.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WebDavProperties.class)
@Slf4j
public class WebDavConfig {

    @Bean
    public SidecarStore webdavSidecarStore(
            @Qualifier("vanceRedisTemplate") StringRedisTemplate redis, WebDavProperties properties) {
        return new SidecarStore(redis, properties);
    }

    @Bean
    public VanceWebDavSecurityManager webdavSecurityManager(
            PasswordService passwordService,
            UserService userService,
            TeamService teamService,
            PermissionService permissionService,
            WebDavProperties properties) {
        return new VanceWebDavSecurityManager(
                passwordService, userService, teamService, permissionService, properties);
    }

    @Bean
    public LockManager webdavLockManager(
            @Qualifier("vanceRedisTemplate") StringRedisTemplate redis, WebDavProperties properties) {
        return new RedisLockManager(redis, properties);
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
        log.info("WebDAV surface enabled at /brain/{{tenant}}/webdav/ (DAV level 2, Redis locks)");
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
