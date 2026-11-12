package de.mhus.vance.shared.irismodel;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Renders the machine-owned manual model document ({@code auto: true}) that the
 * model catalog reads: the prices an endpoint reports and, for an endpoint that
 * owns its model list, the class of the model. Pure, so the format is testable
 * without a document store.
 */
public final class MachineOwnedModelDoc {

    private MachineOwnedModelDoc() {}

    /** The class of a model, stated by the endpoint that owns the list. */
    public record Classification(
            String kind,
            @Nullable String size,
            List<String> capabilities,
            Map<String, Object> costFields,
            @Nullable String costCurrency) {}

    /** The document of one Iris alias. */
    public static String render(IrisModel m) {
        return render(
                m.id(),
                m.id(),
                m.pricing(),
                new Classification(m.kind(), m.size(), m.capabilities(), m.unitCosts(), m.currency()),
                m.contextWindowTokens(),
                m.maxOutputTokens());
    }

    public static String render(
            String slug,
            String wireName,
            IrisModel.@Nullable TokenPricing pricing,
            @Nullable Classification classification) {
        return render(slug, wireName, pricing, classification, null, null);
    }

    public static String render(
            String slug,
            String wireName,
            IrisModel.@Nullable TokenPricing pricing,
            @Nullable Classification classification,
            @Nullable Integer contextWindowTokens,
            @Nullable Integer maxOutputTokens) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Machine-owned by model-discovery — refreshed on every run while auto: true stays.\n");
        yaml.append("# Remove the marker to take ownership; discovery then never touches this file.\n");
        yaml.append("# Prices are endpoint observations (see _vance/model-auto/ for limits).\n");
        yaml.append("auto: true\n");
        if (!slug.equals(wireName)) {
            yaml.append("wireName: ").append(yamlString(wireName)).append('\n');
        }
        if (contextWindowTokens != null) {
            yaml.append("contextWindowTokens: ").append(contextWindowTokens).append('\n');
        }
        if (maxOutputTokens != null) {
            yaml.append("maxOutputTokens: ").append(maxOutputTokens).append('\n');
        }
        if (classification != null) {
            yaml.append("kind: ").append(yamlString(classification.kind())).append('\n');
            if (classification.size() != null) {
                yaml.append("size: ").append(yamlString(classification.size())).append('\n');
            }
            if (!classification.capabilities().isEmpty()) {
                yaml.append("capabilities: [")
                        .append(String.join(
                                ", ",
                                classification.capabilities().stream()
                                        .map(MachineOwnedModelDoc::yamlString)
                                        .toList()))
                        .append("]\n");
            }
            if (classification.costCurrency() != null) {
                yaml.append("costCurrency: ")
                        .append(yamlString(classification.costCurrency()))
                        .append('\n');
            }
            for (Map.Entry<String, Object> cost : classification.costFields().entrySet()) {
                appendCost(yaml, cost.getKey(), cost.getValue());
            }
        }
        if (pricing != null) {
            yaml.append("pricing:\n");
            yaml.append("  currency: ").append(yamlString(pricing.currency())).append('\n');
            yaml.append("  inputPerMTok: ").append(pricing.inputPerMTok()).append('\n');
            yaml.append("  outputPerMTok: ").append(pricing.outputPerMTok()).append('\n');
            if (pricing.cacheReadPerMTok() != null) {
                yaml.append("  cacheReadPerMTok: ")
                        .append(pricing.cacheReadPerMTok())
                        .append('\n');
            }
            if (pricing.cacheWritePerMTok() != null) {
                yaml.append("  cacheWritePerMTok: ")
                        .append(pricing.cacheWritePerMTok())
                        .append('\n');
            }
        }
        return yaml.toString();
    }

    private static void appendCost(StringBuilder yaml, String field, Object value) {
        if (value instanceof Map<?, ?> tiers) {
            yaml.append(field).append(":\n");
            for (Map.Entry<?, ?> tier : tiers.entrySet()) {
                yaml.append("  ")
                        .append(tier.getKey())
                        .append(": ")
                        .append(tier.getValue())
                        .append('\n');
            }
        } else {
            yaml.append(field).append(": ").append(value).append('\n');
        }
    }

    /** Wire names and currencies are untrusted text: one line, escaped, so a newline cannot write the rest of the doc. */
    public static String yamlString(String raw) {
        String escaped = raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
}
