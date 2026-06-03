package de.mhus.vance.brain.addon;

import de.mhus.vance.api.addon.AddonDto;
import de.mhus.vance.shared.addon.AddonDocument;
import de.mhus.vance.shared.addon.AddonService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only addon discovery endpoint for the face-bootstrap.
 *
 * <p>Lives under {@code /face/} (open path — no tenant in URL, no
 * bearer token required) because the browser fetches it before the
 * user is logged in, to decide which Module Federation remotes to
 * register. Only addons with {@code enabled=true} are returned;
 * disabled rows stay invisible from this endpoint.
 *
 * <p>Spec: {@code specification/addon-system.md} §4.
 */
@RestController
@RequiredArgsConstructor
public class AddonController {

    private final AddonService addonService;

    @GetMapping("/face/addons")
    public List<AddonDto> list() {
        return addonService.listEnabled().stream()
                .map(AddonController::toDto)
                .toList();
    }

    private static AddonDto toDto(AddonDocument doc) {
        return AddonDto.builder()
                .name(doc.getName())
                .path(doc.getPath())
                .checksum(doc.getChecksum())
                .build();
    }
}
