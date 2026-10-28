package de.mhus.vance.brain.tools;

import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What a project's tools may require: the pod's capabilities ({@link PodCapabilities})
 * plus what the project's runner provides. A tool that needs only what a runner can bring
 * is in the inventory of a project that has one and absent from the others — the same
 * "listed means it works" rule the pod gate applies, one level down.
 *
 * <p>{@link ToolCapabilities#RUNNER} is true when a runner is registered for the project.
 * Anything else a runner provides ({@link ToolCapabilities#AUDIO_TOOLS}) is what the runner
 * reports about itself, asked at most once a minute: a project is not held up by a runner
 * that is slow, and one that is down simply provides nothing beyond {@code runner} until it
 * answers again.
 */
@Component
public class ProjectCapabilities {

    /** What a runner can add to a pod's capabilities — everything else is the pod's alone. */
    public static final Set<String> RUNNER_PROVIDED = Set.of(ToolCapabilities.RUNNER, ToolCapabilities.AUDIO_TOOLS);

    private static final Duration KNOWN_FOR = Duration.ofSeconds(60);
    private static final Duration UNREACHABLE_FOR = Duration.ofSeconds(15);

    /** {@code reported}: everything the runner said about itself; {@code capabilities}: the part that is a tool capability. */
    private record Known(Set<String> capabilities, Set<String> reported, Instant until) {}

    private final PodCapabilities pod;
    private final RunnerWork runner;
    private final Clock clock;
    private final Map<String, Known> reported = new ConcurrentHashMap<>();

    @Autowired
    public ProjectCapabilities(PodCapabilities pod, RunnerWork runner) {
        this(pod, runner, Clock.systemUTC());
    }

    ProjectCapabilities(PodCapabilities pod, RunnerWork runner, Clock clock) {
        this.pod = pod;
        this.runner = runner;
        this.clock = clock;
    }

    /** The pod's capabilities and those of the project's runner. */
    public Set<String> available(String tenantId, @Nullable String projectId) {
        Set<String> all = new HashSet<>(pod.available());
        all.addAll(fromRunner(tenantId, projectId));
        return all;
    }

    /** {@code true} when every capability {@code tool} requires is available to the project. */
    public boolean supports(Tool tool, String tenantId, @Nullable String projectId) {
        Set<String> required = tool.requiresCapabilities();
        return required.isEmpty() || available(tenantId, projectId).containsAll(required);
    }

    /** Whether {@code tool} could be offered to some project: what it needs is the pod's or a runner's. */
    public boolean dependsOnRunner(Tool tool) {
        Set<String> required = tool.requiresCapabilities();
        return !pod.supports(tool)
                && !required.isEmpty()
                && required.stream().allMatch(c -> pod.available().contains(c) || RUNNER_PROVIDED.contains(c));
    }

    /**
     * Everything the project's runner reports about itself ({@code info}) — also what is no tool
     * capability, like which mechanics (yt-dlp, whisper) it has. Empty without a runner, and for
     * one that does not answer.
     */
    public Set<String> reportedByRunner(String tenantId, @Nullable String projectId) {
        Known known = known(tenantId, projectId);
        return known == null ? Set.of() : known.reported();
    }

    private Set<String> fromRunner(String tenantId, @Nullable String projectId) {
        Known known = known(tenantId, projectId);
        return known == null ? Set.of() : known.capabilities();
    }

    private @Nullable Known known(String tenantId, @Nullable String projectId) {
        Optional<String> runnerId = runner.runnerId(tenantId, projectId);
        if (runnerId.isEmpty()) {
            return null;
        }
        String key = tenantId + "/" + projectId + "/" + runnerId.get();
        Instant now = clock.instant();
        Known known = reported.get(key);
        if (known == null || !known.until().isAfter(now)) {
            known = ask(tenantId, projectId, now);
            reported.put(key, known);
        }
        return known;
    }

    private Known ask(String tenantId, @Nullable String projectId, Instant now) {
        Set<String> capabilities = new HashSet<>();
        capabilities.add(ToolCapabilities.RUNNER);
        try {
            Set<String> everything = runner.reportedCapabilities(tenantId, projectId);
            for (String reportedCapability : everything) {
                if (RUNNER_PROVIDED.contains(reportedCapability)) {
                    capabilities.add(reportedCapability);
                }
            }
            return new Known(Set.copyOf(capabilities), Set.copyOf(everything), now.plus(KNOWN_FOR));
        } catch (RuntimeException e) {
            // Down or slow: the runner tools stay listed (they fail with a message that says why),
            // what it would add stays out until it answers.
            return new Known(Set.copyOf(capabilities), Set.of(), now.plus(UNREACHABLE_FOR));
        }
    }
}
