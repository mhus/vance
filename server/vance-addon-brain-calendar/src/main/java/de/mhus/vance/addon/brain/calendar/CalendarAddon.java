package de.mhus.vance.addon.brain.calendar;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Calendar Brain addon. Discovered by Spring Boot
 * via {@code META-INF/spring/.../AutoConfiguration.imports} and
 * component-scans this package so the {@link CalendarsApplication}
 * service, {@link CalendarPlannerController} REST controller and the
 * {@code calendar_*} server tools register themselves into the Brain
 * context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = CalendarAddon.class)
public class CalendarAddon {
}
