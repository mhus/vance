package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.simpleauth.PermissionGrantDocument;

/** Document ⇄ DTO. Never expose the document over the wire. */
final class PermissionGrantMapper {

    private PermissionGrantMapper() {}

    static PermissionGrantDto toDto(PermissionGrantDocument d) {
        return new PermissionGrantDto(
                d.getId(),
                d.getTenantId(),
                d.getScopeType(),
                d.getScopeId(),
                d.getSubjectType(),
                d.getSubjectId(),
                d.getRole(),
                d.getCreatedBy(),
                d.getCreatedAt() == null ? null : d.getCreatedAt().toEpochMilli());
    }
}
