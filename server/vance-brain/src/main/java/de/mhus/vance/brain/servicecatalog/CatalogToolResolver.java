package de.mhus.vance.brain.servicecatalog;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Finds the tool a catalog entry means. The pack a service is installed as has a name of its own
 * ({@code slack}, {@code slack_rest}, whatever the operator chose), so a trigger names an operation by the part
 * after the pack's prefix ({@code conversations_history}) and this resolves it against the packs the entry
 * describes. A full name ({@code slack_rest__conversations_history}) is taken as it is.
 */
@Component
@RequiredArgsConstructor
public class CatalogToolResolver {

    private final ToolDispatcher tools;

    public String resolve(ServiceCatalogEntry entry, String tool, ToolInvocationContext ctx) {
        if (tool.contains("__")) return tool;
        List<String> names = tools.resolveAll(ctx).stream()
                .map(r -> r.tool().toSpec(r.source().sourceId()))
                .filter(spec -> spec.getService() != null && entry.describes(spec.getService()))
                .map(ToolSpec::getName)
                .toList();
        return pick(entry, tool, names);
    }

    /** The one of {@code names} that ends in {@code __<operation>}; the first by name when several packs offer it. */
    static String pick(ServiceCatalogEntry entry, String operation, List<String> names) {
        String suffix = "__" + operation.toLowerCase(Locale.ROOT);
        return names.stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(suffix))
                .sorted()
                .findFirst()
                .orElseThrow(() -> new ToolException("The service '" + entry.name() + "' offers no operation '"
                        + operation + "' — is its tool pack installed?"));
    }
}
