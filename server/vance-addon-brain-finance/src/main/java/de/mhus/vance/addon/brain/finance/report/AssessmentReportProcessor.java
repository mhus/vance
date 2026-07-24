package de.mhus.vance.addon.brain.finance.report;

import de.mhus.vance.addon.brain.finance.FinanceCalculator;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.NodeSnapshot;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.toolpack.ToolException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code assessment} report → {@code kind: markdown}. An LLM-written prose
 * assessment of the <em>current</em> model — <b>no time range</b>. It computes
 * the snapshot ({@link FinanceCalculator}), lays it out sign-applied (expenses
 * already negative), and hands it to {@link LightLlmService} via the
 * {@code internal: true} recipe {@code finance-report}. This is the one
 * service-backed (non-pure) processor.
 */
@Component
public class AssessmentReportProcessor implements FinanceReportProcessor {

    static final String RECIPE = "finance-report";

    private final LightLlmService lightLlm;

    public AssessmentReportProcessor(LightLlmService lightLlm) {
        this.lightLlm = lightLlm;
    }

    @Override public String type() { return "assessment"; }

    @Override public String title() { return "Assessment (LLM markdown)"; }

    @Override public String outputKind() { return "markdown"; }

    @Override
    public FinanceReport render(FinanceTreeDocument tree, ReportParams params, ReportContext ctx) {
        FinanceNode root = tree.root();
        if (root == null) throw new ToolException("Cannot assess an empty finance-tree.");

        List<NodeSnapshot> snapshot = FinanceCalculator.compute(root, LocalDate.now(ZoneOffset.UTC));
        String model = layout(tree, root, snapshot);

        Map<String, Object> vars = new HashMap<>();
        vars.put("model", model);
        vars.put("title", tree.title() == null ? "" : tree.title());
        String focus = params.getString("focus");
        vars.put("focus", focus == null ? "" : focus);

        String markdown = lightLlm.call(LightLlmRequest.builder()
                .recipeName(RECIPE)
                .userPrompt("Write the assessment of the finance model now.")
                .pebbleVars(vars)
                .tenantId(ctx.tenantId())
                .projectId(ctx.projectId())
                .processId(ctx.processId())
                .build());

        return new FinanceReport("markdown", "text/markdown", markdown, null);
    }

    /**
     * Sign-applied outline: indent = tree depth, each node's per-year figure is
     * the snapshot value (so expense branches read as negative), with base/
     * interest split and a separate one-time note.
     */
    private static String layout(FinanceTreeDocument tree, FinanceNode root,
                                 List<NodeSnapshot> snapshot) {
        Map<String, NodeSnapshot> byName = new HashMap<>();
        for (NodeSnapshot s : snapshot) byName.put(s.name(), s);

        StringBuilder sb = new StringBuilder();
        if (tree.title() != null) sb.append("Model: ").append(tree.title()).append('\n');
        sb.append("(figures per year; negative = net outflow; one-time held separate)\n\n");
        appendNode(root, 0, byName, sb);
        return sb.toString();
    }

    private static void appendNode(FinanceNode node, int depth,
                                   Map<String, NodeSnapshot> byName, StringBuilder sb) {
        NodeSnapshot s = byName.get(node.name());
        sb.append("  ".repeat(depth)).append("- ");
        sb.append(node.title() != null ? node.title() + " (" + node.name() + ")" : node.name());
        if (s != null) {
            sb.append(": ").append(money(s.perYear())).append("/yr");
            if (Math.abs(s.interest()) > 0.005) {
                sb.append(" [base ").append(money(s.base()))
                        .append(", interest ").append(money(s.interest())).append("]");
            }
            if (Math.abs(s.oneTimeSum()) > 0.005) {
                sb.append(", one-time ").append(money(s.oneTimeSum()));
            }
        }
        sb.append('\n');
        for (FinanceNode child : node.children()) appendNode(child, depth + 1, byName, sb);
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%+.2f", v);
    }
}
