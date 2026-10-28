package de.mhus.vance.brain.tools.exec;

import de.mhus.vance.exec.ScratchArea;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The brain's {@link ScratchArea} from {@code vance.exec.scratch-dir}, swept of
 * leftovers once at startup ({@code planning/multi-tenant-isolation.md} §4.4a).
 */
@Configuration
@Slf4j
public class ScratchConfiguration {

    static final String DEFAULT_DIR_NAME = "vance-scratch";

    @Bean
    public ScratchArea scratchArea(ExecProperties properties) {
        String configured = properties.getScratchDir();
        Path root = StringUtils.isBlank(configured)
                ? Path.of(System.getProperty("java.io.tmpdir"), DEFAULT_DIR_NAME)
                : Path.of(configured);
        ScratchArea area = new ScratchArea(root);
        int removed = area.sweep(properties.getScratchSweepAge());
        log.info("Scratch area {} ({} leftover entries removed)", area.root(), removed);
        return area;
    }
}
