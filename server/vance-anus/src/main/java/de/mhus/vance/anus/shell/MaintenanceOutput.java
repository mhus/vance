package de.mhus.vance.anus.shell;

import de.mhus.vance.shared.maintenance.MaintenanceReport;
import de.mhus.vance.shared.maintenance.MaintenanceReport.EntityResult;
import de.mhus.vance.shared.maintenance.MaintenanceReport.UnaccountedCollection;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link MaintenanceReport} for the shell.
 *
 * <p>One renderer for every subject, because the report is one shape: a table
 * of "which handler, how many rows", a total, and the warning section for
 * collections nobody claims. The only thing that differs between deleting a
 * project and deleting a user is the noun in the header.
 */
final class MaintenanceOutput {

    private MaintenanceOutput() {}

    /**
     * @param subjectNoun what the subject is, lower case — {@code "project"},
     *     {@code "user"}. Only used in the header line.
     */
    static String render(MaintenanceReport report, String subjectNoun) {
        String what = " " + subjectNoun + " '" + report.subject() + "' in tenant '"
                + report.tenantId() + "':";
        String header = switch (report.operation()) {
            case INSPECT -> "Contents of" + what;
            case DELETE -> "Deleted" + what;
            case RENAME -> "Renamed" + what;
        };
        String table = Tables.render(
                List.of("ENTITY", "ROWS", "COLLECTIONS", "NOTE"),
                List.<Function<EntityResult, @Nullable Object>>of(
                        EntityResult::handlerId,
                        EntityResult::affected,
                        e -> String.join(",", e.collections()),
                        EntityResult::note),
                report.entities());
        StringBuilder out = new StringBuilder(header)
                .append('\n').append(table)
                .append("\n  total: ").append(report.total());
        if (report.hasUnaccounted()) {
            out.append("\n\nWARNING — collections holding rows for this ").append(subjectNoun)
                    .append(" that no handler\nclaims. They were NOT touched; add a handler"
                            + " for each:");
            for (UnaccountedCollection unaccounted : report.unaccounted()) {
                out.append("\n  ").append(unaccounted.collection())
                        .append(": ").append(unaccounted.count()).append(" row(s)");
            }
        }
        return out.toString();
    }
}
