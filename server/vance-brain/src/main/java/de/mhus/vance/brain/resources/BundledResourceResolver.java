package de.mhus.vance.brain.resources;

import de.mhus.vance.shared.resourceoverride.ResourceOverrideDocument;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideFile;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideRef;
import de.mhus.vance.shared.resourceoverride.ResourceOverrideService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Picks, per section, where the bundled files come from: the classpath, or the
 * valid central version ({@code planning/central-resource-overrides.md} §5).
 *
 * <ul>
 *   <li>Feature off, or section not listed → classpath.</li>
 *   <li>No enabled version, or the valid one has {@code overwrite=false} → classpath.</li>
 *   <li>Valid version with {@code overwrite=true} → its files.</li>
 *   <li>Store unreadable → the last result for the section, or the classpath if
 *       there is none yet (boot).</li>
 * </ul>
 */
@Slf4j
@Component
public class BundledResourceResolver {

    /** {@link #appliedKey} value while no version is enabled. */
    static final String NONE = "none";

    private final ClasspathResourceSource classpath = new ClasspathResourceSource();
    private final @Nullable ResourceOverrideService overrides;
    private final boolean enabled;
    private final Set<String> sections;
    private final Map<String, Applied> applied = new ConcurrentHashMap<>();

    @Autowired
    public BundledResourceResolver(
            @Nullable ResourceOverrideService overrides,
            @Value("${vance.resource-override.central.enabled:false}") boolean enabled,
            @Value("${vance.resource-override.central.sections:}") List<String> sections) {
        this.overrides = overrides;
        this.enabled = enabled;
        this.sections = Set.copyOf(
                sections.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    /** A resolver that always reads the classpath — for callers that run without the feature. */
    public static BundledResourceResolver classpathOnly() {
        return new BundledResourceResolver(null, false, List.of());
    }

    /** Whether {@code section} is read from the central store at all. */
    public boolean isCentralManaged(String section) {
        return enabled && overrides != null && sections.contains(section);
    }

    /** Sections the watcher has to poll. */
    public Set<String> centralSections() {
        return enabled && overrides != null ? sections : Set.of();
    }

    /**
     * Key of the version the last {@link #load} acted on ({@code none} when no
     * version was enabled), or {@code null} if it has not seen the store yet.
     */
    public @Nullable String appliedKey(String section) {
        Applied a = applied.get(section);
        return a == null ? null : a.key;
    }

    /** The files of {@code section} from the source the rules above choose. */
    public BundledResources load(String section) {
        ResourceOverrideService service = overrides;
        if (!isCentralManaged(section) || service == null) {
            return classpathResources(section);
        }
        try {
            Optional<ResourceOverrideRef> ref = service.effective(section);
            if (ref.isEmpty() || !ref.get().overwrite()) {
                BundledResources fromClasspath = classpathResources(section);
                applied.put(
                        section, new Applied(ref.map(ResourceOverrideRef::key).orElse(NONE), fromClasspath));
                return fromClasspath;
            }
            ResourceOverrideDocument doc = service.find(section, ref.get().version())
                    .orElseThrow(() -> new IllegalStateException(
                            "Version " + ref.get().version() + " of '" + section + "' vanished while loading"));
            BundledResources central = BundledResources.central(toFiles(doc.getFiles()), doc.getVersion());
            applied.put(section, new Applied(ref.get().key(), central));
            return central;
        } catch (RuntimeException e) {
            Applied last = applied.get(section);
            if (last != null) {
                log.warn(
                        "Bundled resources '{}': central store unreadable, keeping {}: {}",
                        section,
                        last.resources.origin(),
                        e.toString());
                return last.resources;
            }
            log.warn(
                    "Bundled resources '{}': central store unreadable at start, using the classpath: {}",
                    section,
                    e.toString());
            return classpathResources(section);
        }
    }

    /** The classpath files of {@code section}, whatever the central store says. */
    public BundledResources loadClasspath(String section) {
        return classpathResources(section);
    }

    private BundledResources classpathResources(String section) {
        return new BundledResources(classpath.load(section), BundledResources.CLASSPATH);
    }

    private static List<BundledResourceFile> toFiles(List<ResourceOverrideFile> files) {
        return files.stream()
                .map(f -> new BundledResourceFile(f.getPath(), f.getContent()))
                .toList();
    }

    private record Applied(String key, BundledResources resources) {}
}
