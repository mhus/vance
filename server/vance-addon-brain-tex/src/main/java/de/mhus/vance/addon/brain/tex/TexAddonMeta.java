package de.mhus.vance.addon.brain.tex;

import de.mhus.vance.shared.addon.VanceAddon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Marker bean for the Insights addons tab. */
@Component
public class TexAddonMeta implements VanceAddon {

    @Value("${vance.tex.executor:local}")
    private String defaultExecutorType;

    @Override public String id() { return "tex"; }

    @Override public String displayName() { return "TeX / LaTeX"; }

    @Override public String status() { return "executor=" + defaultExecutorType; }
}
