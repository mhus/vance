package de.mhus.vance.brain.help;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves bundled help / documentation files from the brain's classpath.
 *
 * <p>Layout: {@code resources/help/{lang}/{path...}} — e.g.
 * {@code resources/help/en/recipe-field-docs.md}. The endpoint takes a
 * language and a resource path; if the language-specific file is
 * missing it falls back to {@code en}, and only if that's also missing
 * does it return 404.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the
 * JWT's {@code tid} claim — the help content is generic, but
 * authentication is required to keep the surface uniform.
 */
@RestController
@RequestMapping("/brain/{tenant}/help")
@Slf4j
public class HelpController {

    private static final String FALLBACK_LANG = "en";
    private static final Pattern LANG_PATTERN = Pattern.compile("^[a-z]{2}$");
    /** Allowed characters in the path: letters, digits, dot, hyphen, underscore, slash. */
    private static final Pattern PATH_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");

    private static final Map<String, MediaType> EXTENSION_TYPES = Map.of(
            "md", MediaType.parseMediaType("text/markdown;charset=UTF-8"),
            "txt", MediaType.TEXT_PLAIN,
            "html", MediaType.TEXT_HTML,
            "json", MediaType.APPLICATION_JSON);

    @GetMapping("/{lang}/{*resourcePath}")
    public ResponseEntity<byte[]> get(
            @PathVariable("lang") String lang,
            @PathVariable("resourcePath") String resourcePath) {

        if (!LANG_PATTERN.matcher(lang).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid language code");
        }
        // {*resourcePath} captures with a leading slash; strip it.
        String normalised = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (normalised.isBlank()
                || normalised.contains("..")
                || !PATH_PATTERN.matcher(normalised).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid resource path");
        }

        ClassPathResource primary = new ClassPathResource("help/" + lang + "/" + normalised);
        ClassPathResource resource = primary;
        if (!resource.exists()) {
            if (!FALLBACK_LANG.equals(lang)) {
                resource = new ClassPathResource("help/" + FALLBACK_LANG + "/" + normalised);
            }
            if (!resource.exists()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Help resource not found: " + normalised);
            }
        }

        try (var in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return ResponseEntity.ok()
                    .contentType(mediaTypeFor(normalised))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=300")
                    .body(bytes);
        } catch (IOException e) {
            log.warn("Failed to read help resource '{}': {}", resource, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read help resource");
        }
    }

    private static MediaType mediaTypeFor(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot >= path.length() - 1) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String ext = path.substring(dot + 1).toLowerCase();
        return EXTENSION_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);
    }

    /** Forces UTF-8 byte arrays — kept here so the call site stays terse. */
    @SuppressWarnings("unused")
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
