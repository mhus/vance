package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.brain.project.ProjectActivationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the provisioning check over the projects <em>this pod is running</em>.
 *
 * <p><b>Not an Ursa scheduler entry.</b> A scheduler entry triggers a recipe, a
 * workflow or a script, never a Java service, and adding a fourth trigger kind
 * for one consumer would be the wrong shape.
 *
 * <h2>Why the running projects, after two wrong answers</h2>
 * The first attempt swept {@code findByHomeNode(self)} and was inert: nothing
 * cleared {@code homeNode} on shutdown, so most projects pointed at a pod that
 * had died weeks earlier and no live pod ever saw them. The fix at the time was
 * to sweep <em>everything</em> from the cluster-master pod — which worked, but
 * bought reach by asking the wrong question: a kit update for a project that
 * nobody is running is not news. It costs a check per project per round across
 * the whole installation, and it produces a notice about something that cannot
 * act on it.
 *
 * <p>Both answers were shaped by ownership being broken. It is not any more
 * ({@code planning/project-ownership-lease-design.md}), so the honest question
 * is askable for the first time: <b>what does this pod have up right now?</b>
 * {@link ProjectActivationRegistry} answers it without a query — it is the
 * pod's own memory of what it activated — and the answers of all pods partition
 * the running projects exactly, so "exactly once" needs neither a master lease
 * nor a claim. Load distributes with the projects instead of piling on one pod.
 *
 * <p>Consequence, intended: a dormant project is not checked and produces no
 * notice. It gets provisioned when it next comes up — that is what
 * {@link KitProvisioningLifecycleListener} is for — and its
 * {@code provisioning.yaml} is deliberately <em>not</em> a reason to keep it on
 * a pod (see {@code ProjectOwnerRequirementService}).
 *
 * <p>Reports only. Installing is the other triggers' business (see
 * {@link KitProvisioningCheck}).
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(KitProvisioningProperties.class)
@Slf4j
public class KitProvisioningCheckTick {

    private final KitProvisioningProperties properties;
    private final KitProvisioningCheck check;
    private final ProjectActivationRegistry activationRegistry;

    @Scheduled(
            initialDelayString =
                    "${vance.kits.provisioning.check-initial-delay:PT5M}",
            fixedDelayString = "${vance.kits.provisioning.check-interval:PT4H}")
    public void tick() {
        if (!properties.isCheckEnabled()) return;

        int swept = 0;
        int reported = 0;
        for (String key : activationRegistry.snapshot()) {
            int slash = key.indexOf('/');
            if (slash <= 0) continue;
            String tenantId = key.substring(0, slash);
            String projectName = key.substring(slash + 1);
            swept++;
            try {
                KitProvisioningCheck.Report report = check.check(tenantId, projectName);
                reported += report.reported().size();
            } catch (RuntimeException e) {
                // One project's broken configuration must not end the sweep
                // over the others.
                log.warn("Provisioning check of {}/{} failed: {}",
                        tenantId, projectName, e.toString());
            }
        }
        if (reported > 0) {
            log.info("Provisioning check swept {} running project(s), reported {}",
                    swept, reported);
        } else {
            log.debug("Provisioning check swept {} running project(s), nothing to report",
                    swept);
        }
    }
}
