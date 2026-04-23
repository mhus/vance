/**
 * Project-Group domain — bundles projects that belong together inside a tenant
 * (e.g. „Doktorarbeit", „Vance Dev"). Optional per v1 spec; projects can live
 * flat under a tenant without a group.
 *
 * <p>Colocated: {@link de.mhus.vance.shared.projectgroup.ProjectGroupDocument},
 * package-private {@link de.mhus.vance.shared.projectgroup.ProjectGroupRepository}
 * and {@link de.mhus.vance.shared.projectgroup.ProjectGroupService} — only the
 * service is exposed.
 */
@NullMarked
package de.mhus.vance.shared.projectgroup;

import org.jspecify.annotations.NullMarked;
