package de.mhus.vance.shared.runner;

import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Which runner serves a project: the project's own, else its tenant's, else none
 * ({@code planning/runner.md} R4). The single place that reads and writes
 * {@link RunnerBinding}; the admin shell sets it, the brain resolves it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunnerBindingService {

    private static final Pattern RUNNER_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final TenantService tenants;
    private final ProjectService projects;

    /** Where an effective binding came from. */
    public enum Source {
        PROJECT,
        TENANT
    }

    /** A binding and the level it was set on. */
    public record Effective(RunnerBinding binding, Source source) {}

    /**
     * The runner for a project: the project's own binding, else the tenant's.
     * {@code projectId == null} asks for the tenant-level binding alone.
     */
    public Optional<Effective> resolve(String tenantId, @Nullable String projectId) {
        if (projectId != null) {
            RunnerBinding own = projects.findByTenantAndName(tenantId, projectId)
                    .map(ProjectDocument::getRunner)
                    .orElse(null);
            if (own != null) {
                return Optional.of(new Effective(own, Source.PROJECT));
            }
        }
        return tenants.findByName(tenantId)
                .map(TenantDocument::getRunner)
                .map(binding -> new Effective(binding, Source.TENANT));
    }

    /**
     * Registers a runner for a tenant ({@code projectId == null}) or one project,
     * replacing what was there.
     *
     * @throws IllegalArgumentException when the id, URL or key is not acceptable
     */
    public RunnerBinding set(
            String tenantId, @Nullable String projectId, String runnerId, String url, String recipient) {
        RunnerBinding binding = RunnerBinding.builder()
                .runnerId(validRunnerId(runnerId))
                .url(validUrl(url))
                .recipient(validRecipient(recipient))
                .registeredAt(Instant.now())
                .build();
        write(tenantId, projectId, binding);
        log.info(
                "Runner '{}' registered for {}{} at {}",
                binding.getRunnerId(),
                tenantId,
                projectId == null ? "" : "/" + projectId,
                binding.getUrl());
        return binding;
    }

    /** Removes the binding set on exactly this level; returns whether there was one. */
    public boolean clear(String tenantId, @Nullable String projectId) {
        boolean had = projectId == null
                ? tenants.findByName(tenantId).map(TenantDocument::getRunner).isPresent()
                : projects.findByTenantAndName(tenantId, projectId)
                        .map(ProjectDocument::getRunner)
                        .isPresent();
        if (had) {
            write(tenantId, projectId, null);
            log.info("Runner binding cleared for {}{}", tenantId, projectId == null ? "" : "/" + projectId);
        }
        return had;
    }

    private void write(String tenantId, @Nullable String projectId, @Nullable RunnerBinding binding) {
        if (projectId == null) {
            tenants.setRunner(tenantId, binding);
        } else {
            projects.setRunner(tenantId, projectId, binding);
        }
    }

    static String validRunnerId(String runnerId) {
        if (runnerId == null || !RUNNER_ID.matcher(runnerId).matches()) {
            throw new IllegalArgumentException(
                    "Runner id must be lowercase letters, digits and '-' (at most 63 characters): '" + runnerId + "'");
        }
        return runnerId;
    }

    static String validRecipient(String recipient) {
        if (recipient == null || !AgeKeys.isRecipient(recipient.trim())) {
            throw new IllegalArgumentException("Not an age recipient (age1…): '" + recipient + "'");
        }
        return recipient.trim();
    }

    /** An http(s) base URL without credentials, query or fragment; a trailing slash is dropped. */
    static String validUrl(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a URL: '" + url + "'", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("The runner URL must be http(s)://host[:port]: '" + url + "'");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("The runner URL must not carry credentials, a query or a fragment");
        }
        String text = uri.toString();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }
}
