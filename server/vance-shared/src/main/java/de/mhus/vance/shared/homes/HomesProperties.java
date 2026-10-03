package de.mhus.vance.shared.homes;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.homes.*} — tunables for the per-scope home tree. The homes are
 * deliberately a tree of their own, sibling to the workspace root, so nothing
 * that walks or serves workspace content can reach them (see
 * {@code planning/home-isolation.md} §4).
 */
@Data
@ConfigurationProperties(prefix = "vance.homes")
public class HomesProperties {

    /**
     * Root of the homes tree. Empty (default) = sibling of the configured
     * workspace root ({@code <vance.workspace.root>/../homes}), so both trees
     * share a volume unless the operator separates them. An operator who wants
     * homes on different media sets this explicitly.
     */
    private String root = "";

    /**
     * Coarse size guard per home directory, in bytes. {@code 0} disables the
     * check. A home holds tool caches (maven, npm, pip) as well as granted
     * credentials, and an agent that fills the pod disk gets the pod evicted —
     * so growth is accounted for, even though the budget is generous.
     */
    private long maxBytes = 2L * 1024 * 1024 * 1024;
}
