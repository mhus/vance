package de.mhus.vance.brain.resources;

import de.mhus.vance.shared.document.DocumentService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/** Reads the YAML files of a section from the bundled {@code vance-defaults/_vance/<section>/} tree. */
@Slf4j
final class ClasspathResourceSource {

    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    List<BundledResourceFile> load(String section) {
        String prefix = DocumentService.RESOURCE_PREFIX + "_vance/" + section + "/";
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:" + prefix + "**/*.yaml");
        } catch (IOException e) {
            log.warn("Bundled resources: classpath scan failed for {}: {}", prefix, e.toString());
            return List.of();
        }
        List<BundledResourceFile> files = new ArrayList<>();
        for (Resource resource : resources) {
            String uri = resource.toString();
            int idx = uri.indexOf(prefix);
            if (idx < 0) {
                continue;
            }
            String relPath = uri.substring(idx + prefix.length());
            // strip trailing ']' from "URL [...]" toString forms.
            int closing = relPath.indexOf(']');
            if (closing >= 0) {
                relPath = relPath.substring(0, closing);
            }
            relPath = relPath.replace('\\', '/');
            String content = read(resource);
            if (content != null) {
                files.add(new BundledResourceFile(relPath, content));
            }
        }
        return files;
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Bundled resources: failed to read classpath resource '{}': {}", resource, e.toString());
            return null;
        }
    }
}
