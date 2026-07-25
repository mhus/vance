package de.mhus.vance.anus.all;

import de.mhus.vance.anus.VanceAnusApplication;

/**
 * Dev-bundle entry point. Delegates straight to {@link VanceAnusApplication#main}
 * rather than re-implementing the boot sequence — Anus's {@code main} strips the
 * {@code --sudo}/{@code --setup} flags before Spring Boot sees them and wires up
 * the one-shot shell runners, none of which the bundle wants to duplicate.
 *
 * <p>Anus addons (simpleauth, ...) sit on the classpath via this module's pom
 * dependencies; Spring Boot discovers them through their
 * {@code META-INF/spring/.../AutoConfiguration.imports} files without any code
 * change here.
 */
public final class VanceAnusAllApplication {

    private VanceAnusAllApplication() {}

    public static void main(String[] args) {
        VanceAnusApplication.main(args);
    }
}
