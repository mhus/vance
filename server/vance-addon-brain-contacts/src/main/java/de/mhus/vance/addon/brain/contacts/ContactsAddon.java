package de.mhus.vance.addon.brain.contacts;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the contacts Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans this
 * package so {@link ContactService}, the {@code contact} kind handler, the
 * REST surface and the {@code contact_*} tools register themselves.
 */
@AutoConfiguration
@ComponentScan(
        basePackages = {
            "de.mhus.vance.addon.brain.contacts",
        })
public class ContactsAddon {}
