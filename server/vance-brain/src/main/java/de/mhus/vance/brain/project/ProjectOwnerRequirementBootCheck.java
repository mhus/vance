package de.mhus.vance.brain.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Boot re-check that pins projects holding waiting work which is not a
 * document ({@link ProjectOwnerRequirementService#pinContributedWork}).
 *
 * <p>Ordered just before {@code ProjectStartupReclaimer}: that one releases
 * stale pins and then self-pulls the projects that need an owner, so a hub
 * pinned here is already a candidate for that very pull. Idempotent — every
 * pod runs it, and an unchanged flag writes nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectOwnerRequirementBootCheck {

    private final ProjectOwnerRequirementService ownerRequirementService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    void pinContributedWork() {
        try {
            int pinned = ownerRequirementService.pinContributedWork();
            if (pinned > 0) {
                log.info("ProjectOwnerRequirementBootCheck: pinned {} project(s) holding waiting work", pinned);
            }
        } catch (RuntimeException e) {
            // Worst case a hub stays unplaced until its next recompute — never
            // a reason to fail the boot.
            log.warn("ProjectOwnerRequirementBootCheck: re-derivation failed: {}", e.toString());
        }
    }
}
