package de.mhus.vance.brain.servertool;

import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code vance-server-tool} kind — one server
 * tool under {@code _vance/server-tools/<name>.yaml}, cascade
 * {@code project → _tenant → classpath} (spec
 * {@code specification/public/server-tools.md}). Path-based marker: the
 * tool tree is the identity, the loader never looks at kind tags.
 *
 * <p><b>Validation delegates to the canonical parser.</b> The admin REST
 * used to be the only write path that checked a tool; since the Settings
 * area edits the document directly, the same two checks run here:
 * {@link ServerToolLoader#validateYaml} (a finding means exactly what
 * {@link ServerToolLoader#listAll} does with the body — WARN and skip, the
 * tool silently disappears) and the type lookup {@code ServerToolService}
 * refuses unknown types with.
 *
 * <p>The factory registry is resolved lazily: tool factories pull in large
 * parts of the brain, and a kind handler is constructed with the kind
 * registry the document layer depends on.
 */
@Service
public class ServerToolDocKindHandler implements KindHandler {

    public static final String KIND = "vance-server-tool";

    private final ServerToolLoader loader;
    private final ObjectProvider<ToolFactoryRegistry> factoryRegistry;

    public ServerToolDocKindHandler(ServerToolLoader loader, ObjectProvider<ToolFactoryRegistry> factoryRegistry) {
        this.loader = loader;
        this.factoryRegistry = factoryRegistry;
    }

    @Override
    public String getName() {
        return KIND;
    }

    @Override
    public boolean detectsPath(String documentPath) {
        return toolName(documentPath) != null;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        List<Finding> findings = new ArrayList<>();
        String name = toolName(ctx.docPath());
        if (name == null) {
            findings.add(Finding.warning(
                    target,
                    "server-tool-location",
                    "outside " + ServerToolLoader.SERVER_TOOL_PATH_PREFIX + " — the loader never reads this tool"));
            return findings;
        }
        ServerToolConfig cfg;
        try {
            cfg = loader.validateYaml(name, content);
        } catch (RuntimeException e) {
            findings.add(Finding.error(target, "server-tool-parse", "the loader skips this tool: " + e.getMessage()));
            return findings;
        }
        ToolFactoryRegistry registry = factoryRegistry.getIfAvailable();
        if (registry != null && registry.find(cfg.type()).isEmpty()) {
            findings.add(Finding.error(
                    target,
                    "server-tool-type-unknown",
                    "unknown tool type '" + cfg.type() + "' — available: "
                            + registry.list().stream()
                                    .map(ToolFactory::typeId)
                                    .sorted()
                                    .toList()));
        }
        return findings;
    }

    /** The tool name a path encodes, by the loader's own rule. */
    private static @Nullable String toolName(@Nullable String documentPath) {
        return documentPath == null ? null : ServerToolLoader.nameFromPath(documentPath);
    }
}
