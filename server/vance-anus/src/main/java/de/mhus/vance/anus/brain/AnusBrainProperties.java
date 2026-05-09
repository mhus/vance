package de.mhus.vance.anus.brain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code vance.anus.brain.*}.
 *
 * <p>{@code httpBase} points at the Brain instance Anus should call.
 * For local dev the default ({@code http://localhost:9990}) is fine; in
 * a clustered deployment any pod will do — Brain itself routes calls
 * to the project-owning pod via Layer-1 internal forwarding.
 *
 * <p>{@code adminTokenTtl} bounds how long a freshly-minted admin token
 * lives. Each admin operation mints a fresh token, so this only matters
 * for clock-skew tolerance — keep it short (seconds, not minutes).
 *
 * <p>{@code adminUserTitle} is the display name written to the
 * {@code _vance-admin} record on first creation. Cosmetic.
 */
@ConfigurationProperties(prefix = "vance.anus.brain")
public class AnusBrainProperties {

    private String httpBase = "http://localhost:9990";
    private Duration adminTokenTtl = Duration.ofSeconds(60);
    private String adminUserTitle = "Anus admin (auto-managed)";
    private Duration httpConnectTimeout = Duration.ofSeconds(5);
    private Duration httpRequestTimeout = Duration.ofSeconds(30);

    public String getHttpBase() {
        return httpBase;
    }

    public void setHttpBase(String httpBase) {
        this.httpBase = httpBase;
    }

    public Duration getAdminTokenTtl() {
        return adminTokenTtl;
    }

    public void setAdminTokenTtl(Duration adminTokenTtl) {
        this.adminTokenTtl = adminTokenTtl;
    }

    public String getAdminUserTitle() {
        return adminUserTitle;
    }

    public void setAdminUserTitle(String adminUserTitle) {
        this.adminUserTitle = adminUserTitle;
    }

    public Duration getHttpConnectTimeout() {
        return httpConnectTimeout;
    }

    public void setHttpConnectTimeout(Duration httpConnectTimeout) {
        this.httpConnectTimeout = httpConnectTimeout;
    }

    public Duration getHttpRequestTimeout() {
        return httpRequestTimeout;
    }

    public void setHttpRequestTimeout(Duration httpRequestTimeout) {
        this.httpRequestTimeout = httpRequestTimeout;
    }
}
