package de.mhus.vance.brain.kit.provisioning;

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

    private final ObjectMapper objectMapper;

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
            base = SafeLink.require(base);
        } catch (SafeLink.UnsafeLinkException e) {
            throw new KitException("provisioning entry " + entry
                    + " has a url that is not usable as an endpoint: " + e.getMessage(), e);
        }
        URI uri = URI.create(base + CAPABILITIES_PATH);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (StringUtils.isNotBlank(entry.token())) {
            request.header("Authorization", "Bearer " + entry.token());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new KitException("ode host at " + base + " is not reachable: "
                    + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new KitException("ode host at " + base + " answered HTTP "
                    + response.statusCode() + " when asked what it offers");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
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
            desired.add(new DesiredKit(entry.url(), kitId, revision, entry.authority()));
        }

        if (desired.isEmpty()) {
            log.debug("Ode host at {} offers nothing for {}/{}",
                    base, context.tenantId(), context.projectId());
        }
        return desired;
    }

    private static @org.jspecify.annotations.Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
