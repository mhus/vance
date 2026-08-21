package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.instance.InstanceProperties;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.net.SafeLink;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Fetches a kit from an application that hosts its own.
 *
 * <p>The third {@link KitSourceLoader}. Mechanically it is close to
 * {@link LibraryKitSourceLoader} — http, a bearer token, a zip — with two
 * differences that matter.
 *
 * <p><b>It is a POST, and the body says who is asking.</b> A library
 * serves what a tenant is entitled to and can answer from the url plus a
 * token; an Ode host may assemble the kit for <em>this</em> project, and
 * it has to be able to find the request again in its own log when someone
 * reports that provisioning failed. So instance, tenant and project ride
 * along. What does <b>not</b> ride along is any person — no user id, no
 * display name. Verortung, nicht Identität; see
 * {@code planning/kit-ode-provisioning.md} §7.
 *
 * <p><b>Plus whatever the operator asked for.</b> A provisioning entry may
 * carry {@code params:}, and they travel with the build request — that is
 * how „give me the German variant with the invoicing module" is said.
 * Free-form on purpose: only the far end knows its own options. Note they
 * go to {@code build} and <b>not</b> to {@code capabilities}, which has
 * to stay cacheable and caller-independent; the consequence is that the
 * revision a host declares does not describe these params, so a change
 * to them counts as a change on <i>our</i> side and the local check has
 * to notice it.
 *
 * <p><b>{@code accessUrl} is the url we reached the host at.</b> A host
 * behind a reverse proxy does not reliably know its own external address,
 * and header-guessing is the worse answer. It is sent so the host can
 * declare it as a placeholder — but the value is substituted on
 * <em>this</em> side, so a host cannot answer with a different address
 * than the one that was used. That would be a redirection vector for no
 * gain (§4).
 *
 * <p><b>No signature is expected.</b> The host that writes the kit is the
 * host that delivers it, so a signature would prove nothing beyond the
 * token and TLS. {@link KitSourceType#ODE} therefore defaults to
 * {@code OFF} in {@code KitSignaturePolicy.defaultFor}, deliberately.
 *
 * <p>Two calls may legitimately return different bytes. Nothing here
 * assumes otherwise.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OdeKitSourceLoader implements KitSourceLoader {

    /**
     * Path the host answers a build request on, appended to the
     * configured base url.
     */
    static final String BUILD_PATH = "/kit/build";

    /** Descriptor file name — read before rendering, so never a template. */
    static final String KIT_DESCRIPTOR = "kit.yaml";

    /**
     * Hard stop, not a target. The contract asks for seconds; this is the
     * point at which a slow host fails an install instead of hanging it.
     */
    static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final InstanceProperties instance;
    private final ObjectMapper objectMapper;
    private final PromptTemplateRenderer renderer;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * What an Ode host is told about the request.
     *
     * <p>A record rather than a hand-built json string so the field set is
     * visible in one place — this is the list §3.1 calls closed, and a
     * closed list is easier to keep closed when adding to it means editing
     * a type.
     *
     * @param kit which kit of the host is meant
     * @param instance self-declared label of this installation, or null
     *        when unset. Never a stand-in value: a host reads a missing
     *        field as „unknown" but would log {@code "default"} as a name.
     * @param tenant which tenant is asking
     * @param project which project the kit is for, or null outside an
     *        install
     * @param accessUrl the url this request was sent to
     * @param installId which existing installation this refreshes, or null
     *        on first contact — the host can tell the two apart, and that
     *        is the only thing this field is for
     * @param params what the operator asked this source for. Free-form
     *        and open-ended, unlike the fields above: those say who and
     *        where and are a closed set, this one says <i>what</i> and
     *        only the far end knows its own options. Empty on a
     *        hand-typed install.
     */
    record BuildRequest(
            String kit,
            @Nullable String instance,
            String tenant,
            @Nullable String project,
            String accessUrl,
            @Nullable String installId,
            Map<String, Object> params) {}

    @Override
    public boolean supports(KitSourceType type) {
        return type == KitSourceType.ODE;
    }

    @Override
    public KitRepoLoader.LoadedKit load(
            KitInheritDto source, KitSourceDto config, KitAccess access, Path target) {

        String kitId = source.getPath();
        if (StringUtils.isBlank(kitId)) {
            throw new KitException("an ode kit needs a path: it identifies which kit the host"
                    + " should build (source " + config.getId() + ")");
        }

        // Checked once, here, because the same string is both the request
        // target and a value substituted into files that become tool
        // definitions. A `javascript:` base url is nonsense in either role.
        String accessUrl;
        try {
            accessUrl = SafeLink.require(KitArchive.trimTrailingSlash(config.getUrl()));
        } catch (SafeLink.UnsafeLinkException e) {
            throw new KitException("ode source '" + config.getId()
                    + "' has a url that is not usable as an endpoint: " + e.getMessage(), e);
        }
        URI uri = URI.create(accessUrl + BUILD_PATH);
        String body = serialise(new BuildRequest(
                kitId,
                StringUtils.trimToNull(instance.getName()),
                access.tenantId(),
                access.projectId(),
                accessUrl,
                access.installId(),
                access.params()),
                config);

        log.debug("OdeKitSourceLoader: building '{}' at {} for {}/{}",
                kitId, uri, access.tenantId(), access.projectId());

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        // Optional on purpose: a company host on an internal network is a
        // legitimate configuration, and a library's „no token, no tenant"
        // reasoning does not transfer — the tenant is in the body.
        if (StringUtils.isNotBlank(access.token())) {
            request.header("Authorization", "Bearer " + access.token());
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // toString, not getMessage: several IOException types carry no
            // message, and "is not reachable: null" is the one thing this catch
            // block exists to avoid saying.
            throw new KitException("ode host '" + config.getId() + "' is not reachable: " + e, e);
        }

        if (response.statusCode() != 200) {
            throw new KitException(describeFailure(response.statusCode(), kitId, config));
        }

        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            KitArchive.unpack(zip, target);
        } catch (IOException e) {
            throw new KitException("failed to unpack kit '" + kitId + "' from ode host '"
                    + config.getId() + "'", e);
        }

        Path descriptorFile = target.resolve(KIT_DESCRIPTOR);
        if (!Files.isRegularFile(descriptorFile)) {
            throw new KitException("ode host '" + config.getId() + "' delivered '" + kitId
                    + "' without a " + KIT_DESCRIPTOR);
        }
        KitDescriptorDto descriptor;
        try {
            descriptor = KitYamlMapper.parseDescriptor(Files.readString(descriptorFile));
        } catch (IOException e) {
            throw new KitException("failed to read the delivered kit.yaml", e);
        }

        renderDeclared(target, descriptor, config, accessUrl, access);

        // An ode host has no commits. The version stands in for now; once
        // the provisioning side reads the host's declared tree hash, that
        // is the better stamp and replaces this one.
        String stamp = "ode:" + (descriptor.getVersion() == null
                ? "unversioned" : descriptor.getVersion());
        return new KitRepoLoader.LoadedKit(target, target, stamp, descriptor, false);
    }

    /**
     * Render the files the host declared, in place.
     *
     * <p>Runs here and not in the generic apply path on purpose: a git kit
     * must stay byte-identical to its repository, and a document there may
     * legitimately contain braces. Which files are templates is the
     * host's statement, not our guess.
     *
     * <p>Every failure mode is loud. A declared file that is missing
     * throws rather than being skipped — the whole point of the mechanism
     * is that a placeholder gets a value, and a silent skip would leave a
     * literal {@code {{ accessUrl }}} in a tool definition to fail much
     * later, somewhere unrelated.
     *
     * <p>One thing is not loud, and it is a considered trade: the renderer
     * runs with {@code strictVariables=false}, so a misspelt
     * {@code {{ accessURL }}} renders empty instead of failing. That is
     * consistent with every other template surface in the tree, and a kit
     * author sees the empty value in their own file on the first install.
     */
    private void renderDeclared(
            Path target,
            KitDescriptorDto descriptor,
            KitSourceDto config,
            String accessUrl,
            KitAccess access) {

        List<String> declared = descriptor.getRender();
        if (declared == null || declared.isEmpty()) return;

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("accessUrl", accessUrl);
        context.put("tenant", access.tenantId());
        context.put("project", access.projectId());
        context.put("instance", StringUtils.trimToNull(instance.getName()));

        for (String declaredPath : declared) {
            if (KIT_DESCRIPTOR.equals(declaredPath)) {
                // The descriptor is parsed before this runs, so rendering it
                // would change the file on disk and nothing else. Refusing
                // beats a placeholder that appears to work.
                throw new KitException("ode host '" + config.getId() + "' declared '"
                        + KIT_DESCRIPTOR + "' for rendering — the descriptor is read before"
                        + " templates are applied, so this would have no effect");
            }
            Path file;
            try {
                file = KitArchive.resolveInside(target, declaredPath);
            } catch (IOException e) {
                throw new KitException("ode host '" + config.getId() + "' declared '"
                        + declaredPath + "' for rendering, which escapes the kit directory", e);
            }
            if (!Files.isRegularFile(file)) {
                throw new KitException("ode host '" + config.getId() + "' declared '"
                        + declaredPath + "' for rendering but did not deliver it");
            }
            try {
                String rendered = renderer.renderStructured(Files.readString(file), context);
                Files.writeString(file, rendered == null ? "" : rendered);
            } catch (IOException e) {
                throw new KitException("failed to render '" + declaredPath + "' of kit from ode"
                        + " host '" + config.getId() + "'", e);
            }
        }
        log.debug("OdeKitSourceLoader: rendered {} declared file(s)", declared.size());
    }

    private String serialise(BuildRequest payload, KitSourceDto config) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // Five nullable strings. If this fails it is a server bug, and
            // saying so beats reporting it as an unreachable host.
            throw new KitException("failed to build the request for ode host '"
                    + config.getId() + "'", e);
        }
    }

    /**
     * Turn a status code into something actionable. „Download failed"
     * would hide which of these it was.
     */
    private static String describeFailure(int status, String kitId, KitSourceDto config) {
        return switch (status) {
            case 401, 403 -> "ode host '" + config.getId() + "' rejected the credential";
            case 404 -> "ode host '" + config.getId() + "' does not serve a kit '" + kitId + "'";
            default -> "ode host '" + config.getId() + "' returned HTTP " + status
                    + " for kit '" + kitId + "'";
        };
    }
}
