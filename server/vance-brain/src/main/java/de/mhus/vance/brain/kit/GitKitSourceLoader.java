package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Loads kits that are addressed by <em>where they are</em>: a git repo
 * or a local directory.
 *
 * <p>Both in one loader because {@link KitRepoLoader} already decides
 * between them from the url shape, and splitting that decision across
 * two beans would mean maintaining the same url heuristic twice.
 */
@Service
@RequiredArgsConstructor
public class GitKitSourceLoader implements KitSourceLoader {

    private final KitRepoLoader repoLoader;

    @Override
    public boolean supports(KitSourceType type) {
        return type == KitSourceType.GIT || type == KitSourceType.FOLDER;
    }

    @Override
    public KitRepoLoader.LoadedKit load(
            KitInheritDto source, KitSourceDto config, @Nullable String token, Path target) {
        return repoLoader.load(source, token, target);
    }
}
