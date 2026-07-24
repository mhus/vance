package de.mhus.vance.addon.brain.finance.report;

import de.mhus.vance.addon.brain.finance.FinanceProjector;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.ProjectionPeriod;
import de.mhus.vance.addon.brain.finance.model.ProjectionRow;
import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code table} report → {@code kind: sheet}. A period-over-period matrix:
 * column A = node name, one column per period, a trailing Total column; row 1
 * is the header. Written through {@link SheetCodec} (byte-identical).
 */
@Component
public class TableReportProcessor implements FinanceReportProcessor {

    @Override public String type() { return "table"; }

    @Override public String title() { return "Table (sheet)"; }

    @Override public String outputKind() { return "sheet"; }

    @Override
    public FinanceReport render(FinanceTreeDocument tree, ReportParams params) {
        ReportSupport.Range r = ReportSupport.resolveRange(type(), params);
        FinanceProjection proj = tree.root() == null
                ? new FinanceProjection(List.of(), List.of())
                : FinanceProjector.project(tree.root(), r.from(), r.to(), r.granularity());

        List<ProjectionPeriod> periods = proj.periods();
        List<ProjectionRow> rows = proj.rows();
        int totalCol = 1 + periods.size() + 1; // A(name) + periods + Total

        List<String> schema = new ArrayList<>();
        for (int c = 1; c <= totalCol; c++) schema.add(SheetCodec.columnLetterFromIndex(c));

        List<SheetCell> cells = new ArrayList<>();
        cells.add(cell("A1", "Node"));
        for (int i = 0; i < periods.size(); i++) {
            cells.add(cell(col(2 + i) + "1", periods.get(i).label()));
        }
        cells.add(cell(col(totalCol) + "1", "Total"));

        int row = 2;
        for (ProjectionRow pr : rows) {
            cells.add(cell("A" + row, pr.name()));
            for (int i = 0; i < pr.amounts().size(); i++) {
                cells.add(cell(col(2 + i) + row, ReportSupport.money(pr.amounts().get(i))));
            }
            cells.add(cell(col(totalCol) + row, ReportSupport.money(pr.total())));
            row++;
        }

        SheetDocument sheet =
                new SheetDocument("sheet", schema, 1 + rows.size(), cells, new LinkedHashMap<>());
        String body = SheetCodec.serialize(sheet, ReportSupport.YAML_MIME);
        return new FinanceReport("sheet", ReportSupport.YAML_MIME, body, null);
    }

    private static String col(int index) {
        return SheetCodec.columnLetterFromIndex(index);
    }

    private static SheetCell cell(String field, String data) {
        return new SheetCell(field, data, null, null, new LinkedHashMap<>());
    }
}
