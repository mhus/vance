package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The project-role rows the describe tools report — one shape for users and teams. */
final class RoleRows {

    private RoleRows() {}

    /** {@code {project, role}} per PROJECT assignment of the subject, sorted by project. */
    static List<Map<String, Object>> projectRoles(
            RoleAssignmentService assignments, String tenantId, RoleSubjectType subjectType, String subjectId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        assignments.forSubject(tenantId, subjectType, subjectId).stream()
                .filter(a -> a.getScopeType() == RoleScopeType.PROJECT && a.getProjectRole() != null)
                .sorted(Comparator.comparing(RoleAssignmentDocument::getScopeId))
                .forEach(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("project", a.getScopeId());
                    row.put("role", a.getProjectRole().name());
                    rows.add(row);
                });
        return rows;
    }
}
