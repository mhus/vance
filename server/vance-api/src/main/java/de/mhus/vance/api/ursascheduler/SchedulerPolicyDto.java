package de.mhus.vance.api.ursascheduler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the installation lets cron schedulers do — served so the Web-UI can
 * tell the user, before they save, that an expression finer than the
 * granularity is normalised at registration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("scheduler")
public class SchedulerPolicyDto {

    /** Finest cadence a cron scheduler keeps on this brain. */
    private SchedulerGranularity granularity;
}
