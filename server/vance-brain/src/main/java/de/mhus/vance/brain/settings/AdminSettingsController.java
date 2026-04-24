package de.mhus.vance.brain.settings;

import de.mhus.vance.api.settings.SettingDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.settings.SettingWriteRequest;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Administrative settings endpoints.
 *
 * <p>All paths are tenant-scoped via {@code /brain/{tenant}/...}; the
 * {@code BrainAccessFilter} enforces JWT validity and tenant match before
 * requests reach this controller.
 *
 * <p>Password values never leave the server through {@link #find} or
 * {@link #list}; they are rendered as {@code "[set]"}. Writing a password
 * happens by setting {@code type=PASSWORD} in the body — the value is
 * encrypted server-side.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/settings")
@RequiredArgsConstructor
@Slf4j
public class AdminSettingsController {

    private static final String PASSWORD_MASK = "[set]";

    private final SettingService settingService;

    /**
     * Lists settings in a reference scope. If both {@code referenceType} and
     * {@code referenceId} are omitted, all settings across the tenant are
     * returned. If only {@code key} is given, that key is searched across all
     * scopes of the tenant.
     */
    @GetMapping
    public List<SettingDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "referenceType", required = false) @Nullable String referenceType,
            @RequestParam(value = "referenceId", required = false) @Nullable String referenceId,
            @RequestParam(value = "key", required = false) @Nullable String key) {

        if (referenceType != null && referenceId != null) {
            return settingService.findAll(tenant, referenceType, referenceId).stream()
                    .map(AdminSettingsController::toDto)
                    .toList();
        }
        if (key != null && !key.isBlank()) {
            return settingService.findByKey(tenant, key).stream()
                    .map(AdminSettingsController::toDto)
                    .toList();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Provide either (referenceType + referenceId) or (key)");
    }

    @GetMapping("/{referenceType}/{referenceId}/{key}")
    public SettingDto find(
            @PathVariable("tenant") String tenant,
            @PathVariable("referenceType") String referenceType,
            @PathVariable("referenceId") String referenceId,
            @PathVariable("key") String key) {

        return settingService.find(tenant, referenceType, referenceId, key)
                .map(AdminSettingsController::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Setting not found"));
    }

    @PutMapping("/{referenceType}/{referenceId}/{key}")
    public SettingDto upsert(
            @PathVariable("tenant") String tenant,
            @PathVariable("referenceType") String referenceType,
            @PathVariable("referenceId") String referenceId,
            @PathVariable("key") String key,
            @Valid @RequestBody SettingWriteRequest request) {

        SettingDocument saved = request.getType() == SettingType.PASSWORD
                ? settingService.setEncryptedPassword(tenant, referenceType, referenceId, key, request.getValue())
                : settingService.set(tenant, referenceType, referenceId, key,
                        request.getValue(), request.getType(), request.getDescription());

        log.info("Setting upserted tenant='{}' ref='{}:{}' key='{}' type='{}'",
                tenant, referenceType, referenceId, key, saved.getType());
        return toDto(saved);
    }

    @DeleteMapping("/{referenceType}/{referenceId}/{key}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("referenceType") String referenceType,
            @PathVariable("referenceId") String referenceId,
            @PathVariable("key") String key) {

        Optional<SettingDocument> existing = settingService.find(tenant, referenceType, referenceId, key);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        settingService.delete(tenant, referenceType, referenceId, key);
        log.info("Setting deleted tenant='{}' ref='{}:{}' key='{}'",
                tenant, referenceType, referenceId, key);
        return ResponseEntity.noContent().build();
    }

    private static SettingDto toDto(SettingDocument doc) {
        boolean isPassword = doc.getType() == SettingType.PASSWORD;
        return SettingDto.builder()
                .tenantId(doc.getTenantId())
                .referenceType(doc.getReferenceType())
                .referenceId(doc.getReferenceId())
                .key(doc.getKey())
                .value(isPassword ? (doc.getValue() == null ? null : PASSWORD_MASK) : doc.getValue())
                .type(doc.getType())
                .description(doc.getDescription())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
