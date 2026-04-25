/**
 * Spring-based CLI client (Vance Foot). Hybrid TUI architecture:
 * <ul>
 *   <li>JLine 3 REPL is the default surface — chat input plus slash commands</li>
 *   <li>Lanterna full-screen excursions for menus, browsers, modal dialogs</li>
 *   <li>Picocli for top-level CLI subcommands and slash command argument parsing</li>
 *   <li>Spring Boot 4 for service wiring, configuration and component discovery</li>
 * </ul>
 * Module dependency boundary: only {@code vance-api} from the Vance side, never
 * {@code vance-shared} or {@code vance-brain}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.foot;
