package de.mhus.vance.addon.brain.kanban;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Kanban Brain addon. Discovered by Spring Boot
 * via {@code META-INF/spring/.../AutoConfiguration.imports} and
 * component-scans this package so the {@link KanbanApplication}
 * service, {@link KanbanBoardController} REST controller and the
 * {@code kanban_*} server tools register themselves into the Brain
 * context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = KanbanAddon.class)
public class KanbanAddon {
}
