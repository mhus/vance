package de.mhus.vance.brain.settings;

import de.mhus.vance.api.settings.SettingDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.api.settings.SettingWriteRequest;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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

/**
 * Administrative settings endpoints.
 *
 * <p>All paths are tenant-scoped via {@code /brain/{tenant}/...}; the
 * {@code BrainAccessFilter} enforces JWT validity and tenant match before
 * requests reach this controller.
 *
 * <p>The wire-format keeps four reference types — {@code tenant},
 * {@code user}, {@code project}, {@code think-process} — but storage
 * collapses {@code tenant} and {@code user} onto the project layer:
 * <ul>
 *   <li>wire {@code tenant/<anything>} → storage {@code project/_vance}</li>
 *   <li>wire {@code user/<login>} → storage {@code project/_user_<login>}</li>
 *   <li>wire {@code project/<name>} → storage {@code project/<name>}</li>
 *   <li>wire {@code think-process/<id>} → storage {@code think-process/<id>}</li>
 * </ul>
 * The mapping is symmetric: every read translates the persisted
 * reference back to the wire form, so clients see a consistent view
 * without knowing about the consolidation.
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
     * {@code referenceId} are omitted, the search falls through to
     * {@code key}. If only {@code key} is given, that key is searched
     * across all scopes of the tenant.
     */
    @GetMapping
    public List<SettingDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "referenceType", required = false) @Nullable String referenceType,
            @RequestParam(value = "referenceId", required = false) @Nullable String referenceId,
            @RequestParam(value = "key", required = false) @Nullable String key) {

        if (referenceType != null && referenceId != null) {
            StorageRef ref = mapToStorage(referenceType, referenceId);
            return settingService.findAll(tenant, ref.type(), ref.id()).stream()
                    .map(doc -> toDto(doc, referenceType, referenceId))
                    .toList();
        }
        if (key != null && !key.isBlank()) {
            return settingService.findByKey(tenant, key).stream()
                    .map(AdminSettingsController::toDtoFromStorage)
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

        StorageRef ref = mapToStorage(referenceType, referenceId);
        return settingService.find(tenant, ref.type(), ref.id(), key)
                .map(doc -> toDto(doc, referenceType, referenceId))
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

        StorageRef ref = mapToStorage(referenceType, referenceId);
        SettingDocument saved = request.getType() == SettingType.PASSWORD
                ? settingService.setEncryptedPassword(tenant, ref.type(), ref.id(), key, request.getValue())
                : settingService.set(tenant, ref.type(), ref.id(), key,
                        request.getValue(), request.getType(), request.getDescription());

        log.info("Setting upserted tenant='{}' wire='{}:{}' storage='{}:{}' key='{}' type='{}'",
                tenant, referenceType, referenceId, ref.type(), ref.id(), key, saved.getType());
        return toDto(saved, referenceType, referenceId);
    }

    @DeleteMapping("/{referenceType}/{referenceId}/{key}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("referenceType") String referenceType,
            @PathVariable("referenceId") String referenceId,
            @PathVariable("key") String key) {

        StorageRef ref = mapToStorage(referenceType, referenceId);
        Optional<SettingDocument> existing = settingService.find(tenant, ref.type(), ref.id(), key);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        settingService.delete(tenant, ref.type(), ref.id(), key);
        log.info("Setting deleted tenant='{}' wire='{}:{}' storage='{}:{}' key='{}'",
                tenant, referenceType, referenceId, ref.type(), ref.id(), key);
        return ResponseEntity.noContent().build();
    }

    // ─── Wire ↔ Storage mapping ────────────────────────────────────────────

    private record StorageRef(String type, String id) {}

    /**
     * Translates a wire-format scope into the persisted reference.
     * Tenant- and user-scopes collapse onto the project layer with the
     * synthetic {@code _vance} / {@code _user_<login>} project ids; the
     * other two reference types pass through unchanged.
     */
    private static StorageRef mapToStorage(String wireType, String wireId) {
        return switch (wireType) {
            case SettingService.SCOPE_TENANT ->
                    new StorageRef(SettingService.SCOPE_PROJECT,
                            HomeBootstrapService.VANCE_PROJECT_NAME);
            case SettingService.SCOPE_USER -> {
                if (wireId == null || wireId.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "user-scope requires a referenceId (the user login)");
                }
                yield new StorageRef(SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + wireId);
            }
            case SettingService.SCOPE_PROJECT, SettingService.SCOPE_THINK_PROCESS ->
                    new StorageRef(wireType, wireId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown referenceType '" + wireType + "'");
        };
    }

    /**
     * Translates a stored project reference back to the wire form.
     * The {@code _vance} project becomes wire {@code tenant};
     * {@code _user_<login>} becomes wire {@code user/<login>}; other
     * project ids stay {@code project}.
     */
    private static StorageRef storageToWire(String storedType, String storedId) {
        if (SettingService.SCOPE_PROJECT.equals(storedType)) {
            if (HomeBootstrapService.VANCE_PROJECT_NAME.equals(storedId)) {
                return new StorageRef(SettingService.SCOPE_TENANT, storedId);
            }
            if (storedId != null
                    && storedId.startsWith(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX)) {
                String login = storedId.substring(
                        HomeBootstrapService.HUB_PROJECT_NAME_PREFIX.length());
                return new StorageRef(SettingService.SCOPE_USER, login);
            }
        }
        return new StorageRef(storedType, storedId == null ? "" : storedId);
    }

    private static SettingDto toDto(
            SettingDocument doc, String wireType, String wireId) {
        boolean isPassword = doc.getType() == SettingType.PASSWORD;
        return SettingDto.builder()
                .tenantId(doc.getTenantId())
                .referenceType(wireType)
                .referenceId(wireId)
                .key(doc.getKey())
                .value(isPassword ? (doc.getValue() == null ? null : PASSWORD_MASK) : doc.getValue())
                .type(doc.getType())
                .description(doc.getDescription())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }

    private static SettingDto toDtoFromStorage(SettingDocument doc) {
        StorageRef wire = storageToWire(doc.getReferenceType(), doc.getReferenceId());
        return toDto(doc, wire.type(), wire.id());
    }
}
