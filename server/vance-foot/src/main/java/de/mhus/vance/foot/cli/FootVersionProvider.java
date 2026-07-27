package de.mhus.vance.foot.cli;

import de.mhus.vance.foot.config.FootConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.IVersionProvider;

/**
 * Feeds {@code vance-foot --version} from the build stamp injected by Maven
 * resource filtering ({@code vance.build.*}), so the CLI reports the real
 * reactor version instead of a hardcoded string. Wired as a Spring bean via
 * {@code @Command(versionProvider = ...)}; picocli resolves it through the
 * picocli-spring-boot-starter factory.
 */
@Component
public class FootVersionProvider implements IVersionProvider {

    private final FootConfig config;

    public FootVersionProvider(FootConfig config) {
        this.config = config;
    }

    @Override
    public String[] getVersion() {
        FootConfig.Build build = config.getBuild();
        String line = "vance-foot " + build.getVersion();
        if (build.getTime() != null && !build.getTime().isBlank()) {
            line += " (built " + build.getTime() + ")";
        }
        return new String[] {line};
    }
}
