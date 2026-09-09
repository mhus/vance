package de.mhus.vance.brain.tools.context;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reports the build metadata of the running brain: Maven version, build
 * time, and the git commit/branch the jar was built from (stamped by
 * the git-commit-id plugin; {@code unknown} on unstamped builds).
 *
 * <p>Deferred — no engine needs it in an average turn, but two calls
 * matter when they matter: checking out version-matching sources for
 * analysis ({@code git_checkout commit=…}, see the {@code
 * vance-sources} creator manual) and answering "which version is
 * running" without guessing.
 *
 * <p>Complements {@link WhoamiTool}: that one is the caller's identity,
 * this one is the platform's.
 */
@Component
@RequiredArgsConstructor
public class BrainInfoTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final BrainBuildInfo buildInfo;

    @Override
    public String name() {
        return "brain_info";
    }

    @Override
    public String description() {
        return "Get the build metadata of the running brain: version, build "
                + "time, and the git commit/branch it was built from. Call "
                + "this before checking out version-matching sources "
                + "(git_checkout) or when the exact running release matters.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public boolean contributesPrak() {
        // Build-state probe — never an insight.
        return false;
    }

    @Override
    public String searchHint() {
        return "Brain version / build commit — for version-matching source checkouts";
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", buildInfo.version());
        out.put("buildTime", buildInfo.buildTime());
        out.put("commit", buildInfo.git().commit());
        out.put("branch", buildInfo.git().branch());
        out.put("dirty", buildInfo.git().dirty());
        return out;
    }
}
