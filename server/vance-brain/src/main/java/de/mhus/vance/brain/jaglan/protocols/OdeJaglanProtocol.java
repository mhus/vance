package de.mhus.vance.brain.jaglan.protocols;

import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Any foreign application that embeds {@code vance-ode-jaglan}.
 *
 * <p>The protocol id names the <b>transport</b>, not the first system to speak
 * it: a document library, a media archive and a news source with attachments
 * all arrive here. The contract belongs to {@code vance-ode-jaglan}; this is
 * the reading end of it.
 *
 * <p>Configuration, all in {@code _vance/config/mounts/<name>.yaml}:
 *
 * <ul>
 *   <li>{@code protocol = ode}</li>
 *   <li>{@code baseUrl} — where the source serves its file endpoint, including
 *       the path it configured (default on that side is {@code /ode/files}).
 *       Required.</li>
 *   <li>{@code apiKey} — shared secret, sent as {@code Authorization: Bearer}.
 *       Optional, because the far side may already have its own guard in
 *       front.</li>
 * </ul>
 */
@Component
@Slf4j
public class OdeJaglanProtocol implements JaglanProtocol {

    public static final String ID = "ode";

    private final JaglanHttpClient http;

    public OdeJaglanProtocol() {
        this(new JaglanHttpClient.JdkJaglanHttpClient());
    }

    OdeJaglanProtocol(JaglanHttpClient http) {
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Ode file source";
    }

    @Override
    public JaglanInstance instantiate(JaglanInstanceConfig cfg) {
        if (StringUtils.isBlank(cfg.baseUrl())) {
            // Refused here so the factory drops the mount and it never appears
            // in the tree — better than a folder that opens and then fails on
            // every read.
            throw new IllegalArgumentException(
                    "mount '" + cfg.mount() + "': baseUrl is required for protocol " + ID);
        }
        log.info("Jaglan ode mount '{}' → {}", cfg.mount(), cfg.baseUrl());
        return new OdeJaglanInstance(cfg, http);
    }
}
