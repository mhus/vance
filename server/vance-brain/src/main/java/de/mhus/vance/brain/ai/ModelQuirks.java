package de.mhus.vance.brain.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Pattern-based default for {@code ModelInfo.messageParser()} —
 * applied during {@link ModelCatalog#lookup} when no per-model YAML
 * has set the field explicitly. Single bundled file
 * ({@code vance-defaults/model-quirks.yaml}) covers every provider
 * because the quirks are usually wire-protocol idiosyncrasies of a
 * model family (DeepSeek-V4 trailing-garbage, Gemma-4 tokenizer leaks)
 * that follow the model wherever it's hosted.
 *
 * <h2>File format</h2>
 *
 * <pre>{@code
 * rules:
 *   - match: "deepseek-v4*"
 *     messageParser: "deepseek-v4"
 *   - match: "gemma-4*"
 *     messageParser: "gemma4"
 * }</pre>
 *
 * <ul>
 *   <li>{@code match} is a simple glob over the model wire name
 *       (case-insensitive). Supported: {@code *} (zero-or-more
 *       characters) and {@code ?} (exactly one character).</li>
 *   <li>Rule order = priority. First match wins; subsequent rules for
 *       the same model are ignored.</li>
 * </ul>
 *
 * <p>Layer position: this is the final fallback in the
 * {@code messageParser} cascade (project YAML → tenant YAML →
 * {@code _vance} YAML → bundled per-model YAML → bundled quirks →
 * {@code null}). Specific files always beat patterns.
 */
@Component
@Slf4j
public class ModelQuirks {

    private static final String CLASSPATH = "vance-defaults/model-quirks.yaml";

    private final List<Rule> rules;

    public ModelQuirks() {
        this(new ClassPathResource(CLASSPATH));
    }

    /** Test-friendly constructor. */
    ModelQuirks(Resource resource) {
        this.rules = loadRules(resource);
        log.info("ModelQuirks: loaded {} rule(s) from {}", rules.size(), resource);
    }

    /**
     * Resolve the default {@code messageParser} for {@code modelName},
     * or empty when no rule matches.
     */
    public Optional<String> messageParserFor(@Nullable String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (rule.compiled.matcher(lower).matches()) {
                return Optional.of(rule.messageParser);
            }
        }
        return Optional.empty();
    }

    /** Test diagnostic. */
    int ruleCount() {
        return rules.size();
    }

    private static List<Rule> loadRules(Resource resource) {
        if (!resource.exists()) {
            log.warn("ModelQuirks: {} not found — no quirks applied", resource);
            return List.of();
        }
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            Object parsed = new Yaml().load(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (parsed == null) return List.of();
            if (!(parsed instanceof Map<?, ?> m)) {
                log.warn("ModelQuirks: {} root is not a map — ignored", resource);
                return List.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            root = typed;
        } catch (IOException | RuntimeException e) {
            log.warn("ModelQuirks: failed to read {}: {}", resource, e.toString());
            return List.of();
        }
        Object rulesNode = root.get("rules");
        if (!(rulesNode instanceof List<?> rawRules)) {
            return List.of();
        }
        List<Rule> out = new ArrayList<>(rawRules.size());
        int index = 0;
        for (Object rawRule : rawRules) {
            index++;
            if (!(rawRule instanceof Map<?, ?> rmap)) {
                log.warn("ModelQuirks: rule #{} is not a map — skipped", index);
                continue;
            }
            Object matchRaw = rmap.get("match");
            Object parserRaw = rmap.get("messageParser");
            if (matchRaw == null || parserRaw == null) {
                log.warn("ModelQuirks: rule #{} missing match/messageParser — skipped", index);
                continue;
            }
            String pattern = matchRaw.toString().trim();
            String parser = parserRaw.toString().trim();
            if (pattern.isEmpty() || parser.isEmpty()) {
                log.warn("ModelQuirks: rule #{} has blank match/messageParser — skipped", index);
                continue;
            }
            out.add(new Rule(pattern, parser, globToRegex(pattern)));
        }
        return List.copyOf(out);
    }

    /**
     * Translate a simple glob ({@code *}, {@code ?}) into a
     * case-insensitive anchored regex. Other regex metacharacters are
     * quoted; we deliberately do <i>not</i> support character classes —
     * the file format stays human-trivial.
     */
    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 4);
        sb.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.^$|+(){}[]".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private record Rule(String glob, String messageParser, Pattern compiled) {}
}
