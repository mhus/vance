package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitSecretEncoding;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Refuses a kit layer that ships a credential in the clear from a source
 * that has somewhere for it to sit still.
 *
 * <h2>What the vault password is actually for</h2>
 * It protects a credential <b>at rest in a store the reader fetches from</b>
 * — a git repository anyone reaching it can clone, a library archive that is
 * a file on a server. The password is shared out of band exactly because
 * that store cannot be trusted to hold the secret itself.
 *
 * <p>{@link KitSourceType#ODE} has no such store. The bundle is assembled per
 * request and handed to a caller the host authenticated, over TLS; there is
 * no copy for a vault password to protect, and no out-of-band channel to
 * agree one over — an Ode host provisions a project precisely so that nobody
 * has to type anything. That is the same argument already written down for
 * signatures on {@link KitSourceType#ODE}: the token and TLS say what the
 * extra mechanism would have said.
 *
 * <p>So {@link KitSecretEncoding#PLAIN} is allowed from an Ode source and
 * refused everywhere else, and <b>that restriction is the security
 * statement</b>. There is nothing else holding it up, which is why this runs
 * as a gate rather than as a check somewhere inside the installer.
 *
 * <h2>Per layer, and hard</h2>
 * Per layer for the reason {@link KitSignatureGate} is: a kit's inherits come
 * from their own sources, and an Ode top layer says nothing about a base kit
 * pulled out of a git repository behind it. Every layer passes through
 * {@link KitSourceLoaders#loadFrom} on its own.
 *
 * <p>Hard, because the alternative is the failure this whole gate exists to
 * end. A skipped credential is invisible: the install reports success, the
 * setting is simply absent, and the first symptom is an opaque 401 from
 * whatever the kit configured, days later and nowhere near the cause. A
 * refusal names the file.
 */
@Service
@Slf4j
public class KitPlaintextSecretGate {

    /**
     * Let a loaded layer through, or refuse it.
     *
     * @param kitRoot directory holding {@code kit.yaml}
     * @param config the source this layer came from
     * @throws KitException when the layer ships a plaintext credential and
     *         its source is not one that may
     */
    public void enforce(Path kitRoot, KitSourceDto config) {
        Path settingsRoot = kitRoot.resolve(KitInstaller.SETTINGS_DIR);
        if (!Files.isDirectory(settingsRoot)) return;

        List<String> offending = new ArrayList<>();
        try (Stream<Path> files = Files.list(settingsRoot)) {
            for (Path file : files.sorted().toList()) {
                String filename = file.getFileName().toString();
                if (!filename.endsWith(KitInstaller.SETTING_FILE_SUFFIX)) continue;
                if (!Files.isRegularFile(file)) continue;
                String yaml;
                try {
                    yaml = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new KitException("failed to read " + filename, e);
                }
                // Parsing rather than grepping for the word: `plain` inside a
                // description would match, and a file this gate misread in
                // either direction is worse than one it cannot read at all.
                // A malformed file throws here, which is where a malformed
                // file should stop anyway.
                if (KitYamlMapper.parseSetting(yaml, filename).encoding()
                        != KitSecretEncoding.VAULT) {
                    offending.add(filename);
                }
            }
        } catch (IOException e) {
            throw new KitException("failed to list " + settingsRoot, e);
        }
        if (offending.isEmpty()) return;

        if (config.getType() == KitSourceType.ODE) {
            // Worth a line even when permitted: an operator reading the log
            // after a provisioning run should be able to see that a
            // credential arrived, without it being an incident.
            log.debug("KitPlaintextSecretGate: source '{}' is ODE — accepting delivered "
                    + "credentials in {}", config.getId(), offending);
            return;
        }
        throw new KitException("kit source '" + config.getId() + "' is of type "
                + config.getType() + " and may not deliver a credential in the clear: "
                + offending + " declare `encoding: plain`. Only an ODE source may, because"
                + " its bundle is built per request rather than stored. Encrypt the value"
                + " with a vault password and drop the `encoding:` line, or serve this kit"
                + " from an ODE source.");
    }
}
