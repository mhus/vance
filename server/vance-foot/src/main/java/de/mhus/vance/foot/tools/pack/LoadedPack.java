package de.mhus.vance.foot.tools.pack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A parsed pack definition plus where it came from. The origin decides
 * whether the pack needs the user's consent before it is materialised
 * (see {@code ProjectPackConsent}); the file path is what diagnostics
 * point at when two layers define the same pack name.
 */
public record LoadedPack(FootToolPackConfig config, Path file, PackOrigin origin) {

    public String name() {
        return config.name();
    }

    /**
     * Human-readable summary of what materialising this pack would
     * reach out to — the spawned command for stdio MCP, the endpoint for
     * everything HTTP-shaped. This is both what the consent prompt shows
     * and what gets remembered as the approved shape, so a repo that
     * later swaps the command in has to ask again.
     *
     * <p>Falls back to the type name when a pack carries neither (a
     * malformed definition, which the builder will reject anyway).
     */
    public String reachDescription() {
        Map<String, Object> params = config.parametersOrEmpty();
        Object command = params.get("command");
        if (command instanceof List<?> argv && !argv.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object arg : argv) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(arg);
            }
            return sb.toString();
        }
        if (command instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        for (String key : List.of("url", "postUrl", "specUrl", "baseUrl")) {
            if (params.get(key) instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return "type=" + config.type();
    }
}
