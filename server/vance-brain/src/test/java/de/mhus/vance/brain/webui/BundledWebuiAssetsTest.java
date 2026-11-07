package de.mhus.vance.brain.webui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** What the brain ships under {@code _vance/webui/}: it is served, and a borrowed logo says where it is from. */
class BundledWebuiAssetsTest {

    private static final String ROOT = "classpath*:vance-defaults/_vance/webui/";
    private static final Pattern ICON_LINE = Pattern.compile("(?m)^icon:\\s*(\\S+)\\s*$");
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    void everyBundledAssetHasAnAddressTheServerServes() throws IOException {
        for (Resource resource : resolver.getResources(ROOT + "**/*.*")) {
            String path = relative(resource);
            if (path.endsWith(".license.md")) continue;
            assertThat(WebuiAssetPath.parse(path)).as(path).isPresent();
        }
    }

    @Test
    void everyBundledSvgSurvivesTheSanitizerWithItsDrawing() throws IOException {
        for (Resource resource : resolver.getResources(ROOT + "**/*.svg")) {
            byte[] bytes = read(resource);
            byte[] clean = SvgSanitizer.sanitize(bytes).orElse(null);
            assertThat(clean).as(relative(resource)).isNotNull();
            assertThat(new String(clean, StandardCharsets.UTF_8))
                    .as(relative(resource))
                    .contains("<path");
        }
    }

    @Test
    void everyBundledLogoCarriesALicenceNote() throws IOException {
        for (Resource resource : resolver.getResources(ROOT + "**/*.svg")) {
            String path = relative(resource);
            assertThat(resolver.getResource(ROOT.replace("classpath*:", "classpath:") + path + ".license.md")
                            .exists())
                    .as("licence note of " + path)
                    .isTrue();
        }
    }

    @Test
    void aCatalogEntryThatNamesALogoNamesOneThatIsBundled() throws IOException {
        for (Resource entry : resolver.getResources("classpath*:vance-defaults/_vance/service-catalog/*.yaml")) {
            Matcher icon = ICON_LINE.matcher(new String(read(entry), StandardCharsets.UTF_8));
            if (!icon.find() || !icon.group(1).contains(".")) continue;
            assertThat(resolver.getResource(ROOT.replace("classpath*:", "classpath:") + icon.group(1))
                            .exists())
                    .as(entry.getFilename() + " names " + icon.group(1))
                    .isTrue();
            assertThat(WebuiAssetPath.parse(icon.group(1))).as(icon.group(1)).isPresent();
        }
    }

    private static String relative(Resource resource) throws IOException {
        String url = resource.getURL().toString();
        return url.substring(url.indexOf("_vance/webui/") + "_vance/webui/".length());
    }

    private static byte[] read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
