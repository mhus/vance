package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.net.SafeLink;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks an application which kits a project should have.
 *
 * <p>The first provisioning mechanism, and the reason the axis is open:
 * the host of an application knows what that application needs, and
 * nothing in Vancetope has to be taught about it.
 *
 * <p>One cheap {@code GET} per run. The revision each kit declares is
 * what makes the periodic check affordable — without it, finding out
 * that nothing changed would cost a build on the far side and a download
 * on this one.
 *
 * <p><b>Everything from the far end is parsed defensively.</b> These
 * bytes come from software we do not own. The rule is Centauri's: a
 * broken row is skipped and logged, not fatal — one unusable entry must
 * not cost a project its other four kits. A transport failure is
 * different and does throw: „the host is unreachable" and „the host has
 * nothing for you" are answers a caller has to be able to tell apart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OdeKitProvisioningHandler implements KitProvisioningHandler {

    /** Sub-path the ode kit module answers on; see its properties for why it is fixed. */
    static final String CAPABILITIES_PATH = "/kit/capabilities";

    /**
     * Short on purpose. This runs on a schedule and answers a question
     * nobody is waiting on; a host that cannot say what it offers within
     * this budget is better treated as silent than waited for.
     */
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Ceiling on the capabilities answer. The timeout bounds how long a host
     * may take, not how much it may send — and this runs unattended, against
     * hosts we do not own, on a schedule. A list of kit ids and revisions that
     * does not fit in a megabyte is not a list we should be reading into the
     * heap.
     */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final KitSourceRegistry sources;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String id() {
        return "ode";
    }

    @Override
    public List<DesiredKit> discover(KitProvisioningContext context) {
        KitProvisioningEntry entry = context.entry();
        String base = trimTrailingSlash(entry.url());
        try {
            base = requireHttpEndpoint(SafeLink.require(base));
        } catch (SafeLink.UnsafeLinkException e) {
            throw new KitException("provisioning entry " + entry
                    + " has a url that is not usable as an endpoint: " + e.getMessage(), e);
        }
        requireOdeSource(context.tenantId(), entry, base);

        URI uri = endpoint(base, CAPABILITIES_PATH);

        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
        } catch (IllegalArgumentException e) {
            // Hostless or otherwise unusable target — same reasoning as
            // endpoint() below: a named 400 beats an unhandled 500.
            throw new KitException("provisioning url '" + base
                    + "' is not a usable request target: " + e.getMessage(), e);
        }
        if (StringUtils.isNotBlank(entry.token())) {
            request.header("Authorization", "Bearer " + entry.token());
        }

        HttpResponse<java.io.InputStream> response;
        String body;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream in = response.body()) {
                // Status first: a failure body is not worth reading, and
                // reading it would let a huge error page replace the
                // explanation describeFailure is about to give.
                if (response.statusCode() != 200) {
                    throw new KitException(describeFailure(response.statusCode(), base));
                }
                body = readCapped(in, base);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // toString, not getMessage: several IOException types carry no
            // message, and "is not reachable: null" is the one thing this catch
            // block exists to avoid saying.
            throw new KitException("ode host at " + base + " is not reachable: " + e, e);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new KitException("ode host at " + base + " answered something that is not"
                    + " json when asked what it offers", e);
        }

        JsonNode kits = root.get("kits");
        if (kits == null || !kits.isArray()) {
            // Distinguished from an empty array on purpose: a missing field is
            // a contract the host is not keeping, an empty one is „nothing for
            // this project" and perfectly normal.
            throw new KitException("ode host at " + base
                    + " answered without a 'kits' array when asked what it offers");
        }

        List<DesiredKit> desired = new ArrayList<>();
        for (JsonNode kit : kits) {
            String kitId = text(kit, "id");
            if (StringUtils.isBlank(kitId)) {
                log.warn("Ode host at {} declared a kit without an id — skipped", base);
                continue;
            }
            String revision = text(kit, "revision");
            if (StringUtils.isBlank(revision)) {
                // Kept, not dropped: the kit can still be installed, it just
                // cannot be checked cheaply. Saying so once beats a project
                // that never sees this kit and no line explaining why.
                log.warn("Ode host at {} declares kit '{}' without a revision —"
                        + " change detection is off for it", base, kitId);
                revision = null;
            }
            desired.add(new DesiredKit(
                    entry.url(), kitId, revision, entry.authority(), entry.params()));
        }

        if (desired.isEmpty()) {
            log.debug("Ode host at {} offers nothing for {}/{}",
                    base, context.tenantId(), context.projectId());
        }
        return desired;
    }

    /**
     * Name the likely cause, not just the number.
     *
     * <p>A 404 here almost always means the far end has the module on its
     * classpath but publishes no kit source — the endpoint is conditional on
     * that bean, so it simply does not exist. „HTTP 404" alone sends whoever
     * reads it looking for a wrong url instead.
     */
    private static String describeFailure(int status, String base) {
        return switch (status) {
            case 401, 403 -> "ode host at " + base + " rejected the credential when asked"
                    + " what it offers — check the token in the provisioning entry against"
                    + " the host's api key";
            case 404 -> "ode host at " + base + " serves no kit endpoint (HTTP 404 on "
                    + CAPABILITIES_PATH + ") — the host answers, so this is usually a kit"
                    + " endpoint that is switched off or publishes no kit source, not a"
                    + " wrong url";
            default -> "ode host at " + base + " answered HTTP " + status
                    + " when asked what it offers";
        };
    }

    /**
     * {@link SafeLink} answers "may I put this in front of a person" and its
     * allow-list therefore includes {@code mailto:}. Here the url is a request
     * target, and {@code HttpRequest.newBuilder} rejects a non-http scheme with
     * an {@code IllegalArgumentException} — which nothing catches, so a
     * {@code mailto:} in the provisioning document arrived at the caller as a
     * bare 500 instead of the explained 400 {@code KitAdminController.kitError}
     * produces.
     */
    private static String requireHttpEndpoint(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new KitException("provisioning url '" + url + "' is not an http(s) endpoint");
        }
        return url;
    }

    /**
     * Build the request target, naming the problem if the url will not parse.
     *
     * <p>{@code SafeLink} answers "may a human be shown this" and no longer
     * parses the url as a {@code java.net.URI} — deliberately, because that
     * strictness rejected legitimate addresses. So this is the place that has
     * to say so: {@code URI.create} and {@code HttpRequest.newBuilder} both
     * throw {@code IllegalArgumentException}, which nothing above catches and
     * which reaches the caller as a 500 instead of the explained 400.
     */
    private static URI endpoint(String base, String path) {
        try {
            return URI.create(base + path);
        } catch (IllegalArgumentException e) {
            throw new KitException("provisioning url '" + base
                    + "' is not a usable request target: " + e.getMessage(), e);
        }
    }

    /** Read at most {@link #MAX_RESPONSE_BYTES}, refusing anything longer. */
    private static String readCapped(java.io.InputStream in, String base) throws IOException {
        byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new KitException("ode host at " + base + " answered with more than "
                    + MAX_RESPONSE_BYTES + " bytes when asked what it offers — refusing to"
                    + " read it");
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static @org.jspecify.annotations.Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /**
     * Refuse early when the url this mechanism provisions from is not
     * configured as an {@code ODE} kit source.
     *
     * <p>Without this the run gets further than it should: asking the host
     * works — that is this handler's own http call — and then the <em>fetch</em>
     * resolves the same url through {@code kit-sources.yaml}, finds nothing,
     * guesses {@code GIT} and hands an http endpoint to JGit. What the operator
     * sees is a clone stacktrace, which says nothing about the missing line.
     *
     * <p>This is the one place that knows both axes: it produces kit references
     * that somebody else has to fetch. The check is deliberately narrow — only
     * that <em>this</em> mechanism needs {@code ODE} sources. A future
     * mechanism that lists kits out of a git repo would legitimately produce
     * {@code GIT} ones.
     */
    private void requireOdeSource(String tenantId, KitProvisioningEntry entry, String base) {
        KitSourceType type;
        try {
            type = sources.resolve(tenantId, base).getType();
        } catch (KitException e) {
            // An unreadable kit-sources.yaml is not a diagnostic hiccup — it is
            // the file that decides signature policy, and the fetch would fail
            // on it anyway. Say so here, where the message still names the entry.
            throw e;
        } catch (RuntimeException e) {
            // Resolution is somebody else's logic; failing the whole entry over
            // a diagnostic lookup would be the wrong trade.
            log.debug("Could not resolve a kit source for {}: {}", base, e.toString());
            return;
        }
        if (type != KitSourceType.ODE) {
            throw new KitException("provisioning entry " + entry + " would fetch from a source"
                    + " of type " + type + " — add it to _vance/config/kit-sources.yaml in the"
                    + " _tenant project with 'type: ode' and this exact url, otherwise the"
                    + " fetch treats " + base + " as a git repository");
        }
    }

    private static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
