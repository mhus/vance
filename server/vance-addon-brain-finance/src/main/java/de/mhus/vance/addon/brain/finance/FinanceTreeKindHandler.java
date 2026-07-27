package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link KindHandler} for the {@code finance-tree} kind. Registering this bean
 * (component-scanned by {@link FinanceAddon}) makes {@code finance-tree} a
 * known Vance document kind — accepted by {@code doc_write}, surfaced in tool
 * descriptions, tracked by {@code KindRegistry}.
 *
 * <p>Validation is structural: the body must parse via {@link FinanceTreeCodec}
 * and node {@code name}s must be unique across the tree (they are the business
 * key edges/reports reference). Semantic/computed checks are advisory and
 * never block a write.
 */
@Service
public class FinanceTreeKindHandler implements KindHandler {

    private static final String DEFAULT_MIME = "application/yaml";

    @Override
    public String getName() {
        return FinanceTreeCodec.KIND;
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? FinanceTreeCodec.KIND : ctx.docPath();
        String mime = FinanceTreeCodec.supports(ctx.mimeType()) ? ctx.mimeType() : DEFAULT_MIME;

        FinanceTreeDocument doc;
        try {
            doc = FinanceTreeCodec.parse(content, mime);
        } catch (RuntimeException e) {
            return List.of(Finding.error(target, "finance-parse", "Parse error: " + e.getMessage()));
        }

        List<Finding> out = new ArrayList<>();
        if (doc.root() != null) {
            checkUniqueNames(doc.root(), new HashSet<>(), new HashSet<>(), target, out);
        }
        return out;
    }

    private static void checkUniqueNames(FinanceNode node, Set<String> seen, Set<String> reported,
                                         String target, List<Finding> out) {
        if (!seen.add(node.name()) && reported.add(node.name())) {
            out.add(Finding.error(target, "finance-duplicate-name",
                    "Duplicate node name '" + node.name() + "' — names must be unique in the tree"));
        }
        for (FinanceNode child : node.children()) {
            checkUniqueNames(child, seen, reported, target, out);
        }
    }
}
