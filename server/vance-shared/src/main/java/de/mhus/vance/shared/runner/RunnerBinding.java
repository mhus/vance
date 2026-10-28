package de.mhus.vance.shared.runner;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Which runner serves a tenant or project ({@code planning/runner.md} §6): where it
 * is, who it is, and the age key only it can open requests with. Stored on the
 * tenant and project record and <b>written only through {@link RunnerBindingService}
 * — by the operator, via the admin shell</b>. It is deliberately not a setting: no
 * settings UI, agent, kit or form can reach it, so none can point the brain, which
 * sends it tokens and tenant data, at a host of their choosing.
 *
 * <p>Never part of any DTO that leaves the brain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunnerBinding {

    /** The runner's name; the audience of every token for it is {@code runner:<runnerId>}. */
    private String runnerId = "";

    /** Base URL, e.g. {@code http://runner-acme.vance.svc:9840}; no credentials, no query. */
    private String url = "";

    /** The runner's age recipient ({@code age1…}); requests are encrypted to it. */
    private String recipient = "";

    private @Nullable Instant registeredAt;
}
