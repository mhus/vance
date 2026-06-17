package de.mhus.vance.brain.geocode;

import de.mhus.vance.api.geocode.GeocodeResult;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves place names to coordinates for {@code kind: map}
 * documents. The {@code tenant} path segment is validated by
 * {@code BrainAccessFilter} (auth-required, so the surface stays
 * behind a login) but the geocoding result itself is generic.
 *
 * <p>Returns 404 when the query is empty or could not be resolved —
 * lets the client distinguish "no match" from a 500 / network
 * failure without having to parse an error envelope.
 */
@RestController
@RequestMapping("/brain/{tenant}/geocode")
@RequiredArgsConstructor
@Slf4j
public class GeocodeController {

    private final GeocodeService geocodeService;

    @GetMapping
    public ResponseEntity<GeocodeResult> lookup(
            @PathVariable("tenant") String tenant,
            @RequestParam("q") String query) {
        Optional<GeocodeResult> result = geocodeService.lookup(query);
        return result
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
