package de.mhus.vance.brain.jaglan;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.documents.MountSearchOutcome;
import de.mhus.vance.api.mount.MountedSource;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.document.jaglan.JaglanAccessException;
import de.mhus.vance.shared.document.jaglan.JaglanPort;
import de.mhus.vance.shared.document.jaglan.JaglanUnavailableException;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The one entry point for reaching a mounted source — and the brain-side
 * implementation of {@link JaglanPort}, which is what makes the {@code _ext/}
 * namespace work at all: without this bean in the context, mounted paths
 * resolve to nothing (see {@code JaglanPort}).
 *
 * <p>Its own job is small. Resolving configuration is
 * {@link JaglanSourceFactory}'s, caching self-descriptions is
 * {@link JaglanCapabilitiesCache}'s, and keeping shell rows in step is
 * {@code JaglanShellService}'s in {@code vance-shared}. What is left here is
 * the part that belongs to neither: finding the instance behind a mount name,
 * and <b>turning protocol failures into the two answers the document layer
 * can act on</b>.
 *
 * <p>That translation is the reason the exception types are split across
 * modules. A protocol only knows "I could not do this and here is whether the
 * source refused"; it has no business deciding that a refusal becomes an HTTP
 * 409 and an outage becomes a retry. Only the document layer knows what it
 * wants to tell REST and the tool surface, so {@link JaglanProtocolException}
 * lands here and leaves as {@link JaglanAccessException} (stable, stop asking)
 * or {@link JaglanUnavailableException} (transient, try later).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JaglanService implements JaglanPort {

    private final JaglanSourceFactory factory;
    private final JaglanCapabilitiesCache capabilities;
    private final MetricService metrics;

    @Override
    public List<MountedSource> mounts(String tenantId, String projectId) {
        List<JaglanInstance> instances = factory.assemble(tenantId, projectId);
        List<MountedSource> out = new ArrayList<>(instances.size());
        for (JaglanInstance instance : instances) {
            // peek, never warm: this runs inside folder listings.
            JaglanCapabilities caps =
                    capabilities.peek(tenantId, projectId, instance.mount());
            out.add(describe(instance, caps,
                    capabilities.failedRecently(tenantId, projectId, instance.mount())));
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<MountedStat> stat(
            String tenantId, String projectId, String mount, String pathInMount) {
        JaglanInstance instance = require(tenantId, projectId, mount);
        capabilities.warm(tenantId, projectId, instance);
        try {
            Optional<MountedStat> result = instance.stat(pathInMount);
            metrics.counter("vance.jaglan.stat", "outcome",
                    result.isPresent() ? "found" : "absent").increment();
            return result;
        } catch (RuntimeException e) {
            throw translate(mount, "stat", e);
        }
    }

    @Override
    public List<MountedStat> list(
            String tenantId, String projectId, String mount, String pathInMount) {
        JaglanInstance instance = require(tenantId, projectId, mount);
        capabilities.warm(tenantId, projectId, instance);
        try {
            List<MountedStat> entries = instance.list(pathInMount);
            metrics.counter("vance.jaglan.list", "outcome", "success").increment();
            return entries;
        } catch (RuntimeException e) {
            metrics.counter("vance.jaglan.list", "outcome", "failed").increment();
            throw translate(mount, "list", e);
        }
    }

    @Override
    public MountSearchResult search(
            String tenantId, String projectId, String mount, String query, int limit) {
        JaglanInstance instance = require(tenantId, projectId, mount);
        // Warmed, not peeked. A search is one explicit action against one named
        // mount, so it can afford the capabilities call that a folder listing
        // cannot — and deciding this from a cold cache answered "this source
        // cannot search" about a source that can.
        JaglanCapabilities caps = capabilities.warm(tenantId, projectId, instance);
        if (caps == null) {
            // Asked and could not find out: unreachable, not incapable.
            metrics.counter("vance.jaglan.search", "outcome", "unavailable").increment();
            return new MountSearchResult(List.of(), MountSearchOutcome.UNAVAILABLE);
        }
        if (!caps.canSearch()) {
            // Not asked at all, and the caller must be able to tell.
            metrics.counter("vance.jaglan.search", "outcome", "unsupported").increment();
            return new MountSearchResult(List.of(), MountSearchOutcome.UNSUPPORTED);
        }
        try {
            List<MountedStat> hits = instance.search(query, limit);
            metrics.counter("vance.jaglan.search", "outcome", "success").increment();
            return new MountSearchResult(hits, MountSearchOutcome.DELEGATED);
        } catch (RuntimeException e) {
            metrics.counter("vance.jaglan.search", "outcome", "failed").increment();
            throw translate(mount, "search", e);
        }
    }

    @Override
    public InputStream open(
            String tenantId, String projectId, String mount, String pathInMount) {
        JaglanInstance instance = require(tenantId, projectId, mount);
        try {
            InputStream stream = instance.open(pathInMount);
            metrics.counter("vance.jaglan.open", "outcome", "success").increment();
            return stream;
        } catch (RuntimeException e) {
            metrics.counter("vance.jaglan.open", "outcome", "failed").increment();
            throw translate(mount, "open", e);
        }
    }

    @Override
    public MountedStat write(
            String tenantId, String projectId, String mount, String pathInMount,
            InputStream content) {
        JaglanInstance instance = require(tenantId, projectId, mount);
        try {
            MountedStat stat = instance.write(pathInMount, content);
            metrics.counter("vance.jaglan.write", "outcome", "success").increment();
            return stat;
        } catch (RuntimeException e) {
            metrics.counter("vance.jaglan.write", "outcome", "failed").increment();
            throw translate(mount, "write", e);
        }
    }

    @Override
    public void delete(String tenantId, String projectId, String mount, String pathInMount) {
        JaglanInstance instance = require(tenantId, projectId, mount);
        try {
            instance.delete(pathInMount);
            metrics.counter("vance.jaglan.delete", "outcome", "success").increment();
        } catch (RuntimeException e) {
            metrics.counter("vance.jaglan.delete", "outcome", "failed").increment();
            throw translate(mount, "delete", e);
        }
    }

    /** Forget a mount's configuration and self-description — the explicit
     *  refresh behind a "reload mounts" gesture. */
    public void refresh(String tenantId, String projectId, @Nullable String mount) {
        if (mount != null) {
            capabilities.evict(tenantId, projectId, mount);
        }
        factory.evict(tenantId, projectId);
    }

    // ── internals ────────────────────────────────────────────────────

    private JaglanInstance require(String tenantId, String projectId, String mount) {
        JaglanInstance instance = factory.find(tenantId, projectId, mount);
        if (instance == null) {
            // Not configured is our own answer, not the source's — and it is
            // permanent until someone edits settings, so it refuses rather
            // than inviting a retry.
            throw new JaglanAccessException(mount,
                    "no mount '" + mount + "' configured in " + tenantId + "/" + projectId);
        }
        return instance;
    }

    /**
     * Protocol failure → the two answers the document layer can act on.
     *
     * <p>An unmapped {@link RuntimeException} counts as <b>transient</b>. That
     * is the safer default of the two: treating an unknown fault as a refusal
     * would delete shell rows and tell a reader the file does not exist, while
     * treating it as an outage keeps the last answer and retries later.
     */
    private RuntimeException translate(String mount, String op, RuntimeException e) {
        if (e instanceof JaglanAccessException || e instanceof JaglanUnavailableException) {
            return e;
        }
        if (e instanceof JaglanProtocolException protocolFailure) {
            if (protocolFailure.isRefused()) {
                return new JaglanAccessException(mount, protocolFailure.getMessage());
            }
            return new JaglanUnavailableException(mount, protocolFailure.getMessage(), e);
        }
        log.warn("Jaglan: mount '{}' {} failed with an unmapped fault: {}",
                mount, op, e.toString());
        return new JaglanUnavailableException(mount,
                "mount '" + mount + "' " + op + " failed: " + e, e);
    }

    /**
     * @param failedRecently whether the declaration was asked for and the ask
     *        failed. Separates the two reasons {@code caps} can be null, which
     *        must not read the same: a mount whose declaration simply has not
     *        been fetched yet is fine, and calling that an outage makes every
     *        healthy mount look broken for the first minutes after a restart.
     */
    private static MountedSource describe(
            JaglanInstance instance, @Nullable JaglanCapabilities caps, boolean failedRecently) {
        if (caps == null) {
            // Reported either way, not hidden: "not configured" and "not
            // answering right now" are different facts, and only the first
            // justifies absence from the tree. But only a real failure gets a
            // status line — an absent one means "nothing to report yet".
            return new MountedSource(instance.mount(), null, instance.protocolId(),
                    MountAccess.UNKNOWN, null,
                    failedRecently ? "source did not answer" : null, null,
                    /* canSearch */ false);
        }
        return new MountedSource(
                instance.mount(), caps.displayName(), instance.protocolId(),
                caps.access(), caps.itemCount(), null, caps.metadataTtl(), caps.canSearch());
    }
}
