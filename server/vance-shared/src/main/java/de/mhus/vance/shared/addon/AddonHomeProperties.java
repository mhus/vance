package de.mhus.vance.shared.addon;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem layout of unpacked addons, mirroring the Docker
 * entrypoint's {@code ADDONS_HOME} / {@code ADDONS_CACHE} env vars.
 * Defaults match the brain image; outside of the container (IDE dev
 * run, qa) the paths typically do not exist — consumers must tolerate
 * missing directories without erroring.
 */
@ConfigurationProperties(prefix = "vance.addons")
public class AddonHomeProperties {

    /** Root of the unpacked-addon tree: {@code <home>/<id>/<version>/…}. */
    private String home = "/shared/addons";

    /** Cache directory the entrypoint downloads URL-sourced .vabs into. */
    private String cache = "/shared/addons-cache";

    public String getHome() { return home; }
    public void setHome(String home) { this.home = home; }

    public String getCache() { return cache; }
    public void setCache(String cache) { this.cache = cache; }
}
