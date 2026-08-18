package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.InheritArtefactsDto;
import de.mhus.vance.api.kit.KitArtefactDto;
import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.kit.KitPolicyAction;
import de.mhus.vance.api.kit.KitPolicyDto;
import de.mhus.vance.api.kit.KitPolicyRuleDto;
import de.mhus.vance.api.kit.KitSignatureDto;
import de.mhus.vance.api.kit.KitSignaturePolicy;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.kit.KitSourcesDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.kit.KitException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Map between YAML strings and the kit DTOs. Uses SnakeYAML for both
 * directions — read via {@code Yaml.load} into untyped maps and walk
 * the structure manually so a malformed file fails with a clear
 * message rather than a stack trace.
 *
 * <p>All methods are stateless utilities; instantiation is unnecessary.
 */
public final class KitYamlMapper {

    private KitYamlMapper() {}

    // ──────────────────── kit.yaml ────────────────────

    public static KitDescriptorDto parseDescriptor(String yamlText) {
        return parseDescriptorMap(loadMap(yamlText, "kit.yaml"), "kit.yaml");
    }

    /**
     * Shared by the standalone {@code kit.yaml} and by the
     * {@code descriptor:} block embedded in an install record — one
     * grammar, so a descriptor never means two different things.
     */
    private static KitDescriptorDto parseDescriptorMap(Map<String, Object> map, String label) {
        String name = requireString(map, "name", label);
        String description = requireString(map, "description", label);
        String version = stringOrNull(map.get("version"));
        boolean hasEncryptedSecrets = booleanOrFalse(map.get("hasEncryptedSecrets"));
        boolean artifact = booleanOrFalse(map.get("artifact"));
        boolean installable = booleanOr(map.get("installable"), true);
        boolean sealed = booleanOrFalse(map.get("sealed"));

        // Spec: kits.md §3.2 — a kit that is neither installable nor
        // inheritable cannot be used at all. Reject the descriptor up
        // front rather than failing later with a confusing message.
        if (!installable && sealed) {
            throw new KitException(
                    label + ": 'installable: false' and 'sealed: true' together would make"
                            + " the kit unusable (no direct import, no inherit). Pick one.");
        }

        List<KitInheritDto> inherits = new ArrayList<>();
        Object inheritsRaw = map.get("inherits");
        if (inheritsRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (!(element instanceof Map<?, ?> nested)) {
                    throw new KitException(label + " inherits[" + i + "] must be a map");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) nested;
                String url = requireString(e, "url", label + " inherits[" + i + "]");
                inherits.add(KitInheritDto.builder()
                        .url(url)
                        .path(stringOrNull(e.get("path")))
                        .branch(stringOrNull(e.get("branch")))
                        .commit(stringOrNull(e.get("commit")))
                        .build());
            }
        } else if (inheritsRaw != null) {
            throw new KitException(label + " inherits must be a list");
        }

        return KitDescriptorDto.builder()
                .name(name)
                .description(description)
                .version(version)
                .inherits(inherits)
                .hasEncryptedSecrets(hasEncryptedSecrets)
                .artifact(artifact)
                .installable(installable)
                .sealed(sealed)
                .vendor(stringOrNull(map.get("vendor")))
                .license(stringOrNull(map.get("license")))
                .homepage(stringOrNull(map.get("homepage")))
                // Delivery-written fields. Parsed wherever a descriptor comes
                // from, because refusing them for git kits would only mean a
                // shop kit re-imported from a checkout loses its purchase
                // trail — and they carry no authority anyway until a
                // signature covers them.
                .licensedTo(stringOrNull(map.get("licensedTo")))
                .purchaseId(stringOrNull(map.get("purchaseId")))
                .licenseExpiresAt(parseInstant(map.get("licenseExpiresAt")))
                // Same grammar as the user's config document — one policy
                // syntax to learn, whether you author kits or install them.
                .policy(map.get("policy") == null ? null : parsePolicy(map.get("policy"), label))
                .build();
    }

    public static String writeDescriptor(KitDescriptorDto descriptor) {
        return dump(descriptorMap(descriptor));
    }

    /** Serialisable form of a descriptor, shared by kit.yaml and the record's {@code descriptor:}. */
    private static Map<String, Object> descriptorMap(KitDescriptorDto descriptor) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", descriptor.getName());
        root.put("description", descriptor.getDescription());
        if (descriptor.getVersion() != null) {
            root.put("version", descriptor.getVersion());
        }
        if (descriptor.getVendor() != null) root.put("vendor", descriptor.getVendor());
        if (descriptor.getLicense() != null) root.put("license", descriptor.getLicense());
        if (descriptor.getHomepage() != null) root.put("homepage", descriptor.getHomepage());
        if (descriptor.getLicensedTo() != null) {
            root.put("licensedTo", descriptor.getLicensedTo());
        }
        if (descriptor.getPurchaseId() != null) {
            root.put("purchaseId", descriptor.getPurchaseId());
        }
        if (descriptor.getLicenseExpiresAt() != null) {
            root.put("licenseExpiresAt", descriptor.getLicenseExpiresAt().toString());
        }
        if (descriptor.isHasEncryptedSecrets()) {
            root.put("hasEncryptedSecrets", true);
        }
        // Visibility flags only round-trip when they deviate from the
        // default — keeps the export YAML noise-free for normal kits.
        if (descriptor.isArtifact()) {
            root.put("artifact", true);
        }
        if (!descriptor.isInstallable()) {
            root.put("installable", false);
        }
        if (descriptor.isSealed()) {
            root.put("sealed", true);
        }
        if (descriptor.getPolicy() != null) {
            root.put("policy", policyMap(descriptor.getPolicy()));
        }
        if (descriptor.getInherits() != null && !descriptor.getInherits().isEmpty()) {
            List<Map<String, Object>> inherits = new ArrayList<>();
            for (KitInheritDto i : descriptor.getInherits()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("url", i.getUrl());
                if (i.getPath() != null) e.put("path", i.getPath());
                if (i.getBranch() != null) e.put("branch", i.getBranch());
                if (i.getCommit() != null) e.put("commit", i.getCommit());
                inherits.add(e);
            }
            root.put("inherits", inherits);
        }
        return root;
    }

    // ──────────────────── kits/installed/<id>.yaml ────────────────────

    /**
     * Parse an install record. Unlike the manifest parser this is strict
     * about {@code id} — a record without one cannot be addressed for
     * update or uninstall, so silently tolerating it would produce a
     * ghost entry the user cannot get rid of.
     */
    public static KitInstalledRecordDto parseInstalledRecord(String yamlText) {
        final String label = "kits/installed/*.yaml";
        Map<String, Object> map = loadMap(yamlText, label);

        String id = requireString(map, "id", label);

        Object kitRaw = map.get("kit");
        if (!(kitRaw instanceof Map<?, ?> kitMap)) {
            throw new KitException(label + " must have a 'kit' map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> kitTyped = (Map<String, Object>) kitMap;
        KitMetadataDto metadata = KitMetadataDto.builder()
                .name(requireString(kitTyped, "name", label + " kit"))
                .description(requireString(kitTyped, "description", label + " kit"))
                .version(stringOrNull(kitTyped.get("version")))
                .build();

        Object originRaw = map.get("origin");
        if (!(originRaw instanceof Map<?, ?> originMap)) {
            throw new KitException(label + " must have an 'origin' map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> originTyped = (Map<String, Object>) originMap;
        KitOriginDto origin = KitOriginDto.builder()
                .url(requireString(originTyped, "url", label + " origin"))
                .path(stringOrNull(originTyped.get("path")))
                .branch(stringOrNull(originTyped.get("branch")))
                .commit(stringOrNull(originTyped.get("commit")))
                .installedAt(parseInstant(originTyped.get("installedAt")))
                .installedBy(stringOrNull(originTyped.get("installedBy")))
                .build();

        KitDescriptorDto descriptor = null;
        Object descriptorRaw = map.get("descriptor");
        if (descriptorRaw instanceof Map<?, ?> descriptorMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) descriptorMap;
            descriptor = parseDescriptorMap(typed, label + " descriptor");
        }

        KitArtefactsDto artefacts = KitArtefactsDto.builder().build();
        Object artefactsRaw = map.get("artefacts");
        if (artefactsRaw instanceof Map<?, ?> artefactsMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) artefactsMap;
            artefacts = KitArtefactsDto.builder()
                    .documents(parseArtefactList(typed.get("documents"), "path", label))
                    .settings(parseArtefactList(typed.get("settings"), "key", label))
                    .build();
        }

        return KitInstalledRecordDto.builder()
                .id(id)
                .kit(metadata)
                .origin(origin)
                .descriptor(descriptor)
                .artefacts(artefacts)
                .hasEncryptedSecrets(booleanOrFalse(map.get("hasEncryptedSecrets")))
                .signatureStatus(parseSignatureStatus(map.get("signatureStatus")))
                .sourceId(stringOrNull(map.get("sourceId")))
                .build();
    }

    /**
     * Tolerant on read: an unknown status is treated as absent rather
     * than failing the whole record. A value we cannot interpret says
     * nothing, and a record that cannot be parsed hides an installed kit
     * from its owner.
     */
    private static @Nullable KitSignatureStatus parseSignatureStatus(@Nullable Object raw) {
        String value = stringOrNull(raw);
        if (value == null) return null;
        try {
            return KitSignatureStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<KitArtefactDto> parseArtefactList(
            @Nullable Object raw, String idField, String label) {
        List<KitArtefactDto> out = new ArrayList<>();
        if (raw == null) return out;
        if (!(raw instanceof List<?> list)) {
            throw new KitException(label + ": artefacts." + idField + " section must be a list");
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> nested)) continue;
            Map<String, Object> e = (Map<String, Object>) nested;
            String id = stringOrNull(e.get(idField));
            if (id == null) continue;
            out.add(KitArtefactDto.builder()
                    .id(id)
                    .hash(stringOrNull(e.get("hash")))
                    .layer(stringOrNull(e.get("layer")))
                    .build());
        }
        return out;
    }

    public static String writeInstalledRecord(KitInstalledRecordDto record) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", record.getId());

        Map<String, Object> kit = new LinkedHashMap<>();
        kit.put("name", record.getKit().getName());
        kit.put("description", record.getKit().getDescription());
        if (record.getKit().getVersion() != null) {
            kit.put("version", record.getKit().getVersion());
        }
        root.put("kit", kit);

        Map<String, Object> origin = new LinkedHashMap<>();
        KitOriginDto o = record.getOrigin();
        origin.put("url", o.getUrl());
        if (o.getPath() != null) origin.put("path", o.getPath());
        if (o.getBranch() != null) origin.put("branch", o.getBranch());
        if (o.getCommit() != null) origin.put("commit", o.getCommit());
        if (o.getInstalledAt() != null) origin.put("installedAt", o.getInstalledAt().toString());
        if (o.getInstalledBy() != null) origin.put("installedBy", o.getInstalledBy());
        root.put("origin", origin);

        if (record.getDescriptor() != null) {
            root.put("descriptor", descriptorMap(record.getDescriptor()));
        }

        Map<String, Object> artefacts = new LinkedHashMap<>();
        KitArtefactsDto a = record.getArtefacts();
        if (a != null) {
            if (a.getDocuments() != null && !a.getDocuments().isEmpty()) {
                artefacts.put("documents", writeArtefactList(a.getDocuments(), "path"));
            }
            if (a.getSettings() != null && !a.getSettings().isEmpty()) {
                artefacts.put("settings", writeArtefactList(a.getSettings(), "key"));
            }
        }
        root.put("artefacts", artefacts);

        if (record.isHasEncryptedSecrets()) {
            root.put("hasEncryptedSecrets", true);
        }
        if (record.getSignatureStatus() != null) {
            root.put("signatureStatus",
                    record.getSignatureStatus().name().toLowerCase(Locale.ROOT));
        }
        if (record.getSourceId() != null) {
            root.put("sourceId", record.getSourceId());
        }
        return dump(root);
    }

    private static List<Map<String, Object>> writeArtefactList(
            List<KitArtefactDto> artefacts, String idField) {
        List<Map<String, Object>> out = new ArrayList<>(artefacts.size());
        for (KitArtefactDto a : artefacts) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put(idField, a.getId());
            if (a.getHash() != null) e.put("hash", a.getHash());
            if (a.getLayer() != null) e.put("layer", a.getLayer());
            out.add(e);
        }
        return out;
    }

    // ──────────────────── kits/config/<id>.yaml ────────────────────

    /**
     * Parse the optional user-authored config document. Hand-written, so
     * every violation names the offending value — this is the one kit
     * file a human edits directly.
     */
    public static KitConfigDto parseConfig(String yamlText) {
        final String label = "kits/config/*.yaml";
        Map<String, Object> map = loadMap(yamlText, label);

        Integer sortIndex = null;
        Object sortRaw = map.get("sortIndex");
        if (sortRaw instanceof Number n) {
            sortIndex = n.intValue();
        } else if (sortRaw != null) {
            try {
                sortIndex = Integer.valueOf(sortRaw.toString().trim());
            } catch (NumberFormatException e) {
                throw new KitException(label + ": sortIndex must be a number, got '" + sortRaw + "'");
            }
        }

        return KitConfigDto.builder()
                .sortIndex(sortIndex)
                // Absent means "no opinion", which is what lets the kit's own
                // suggestion through — not the same as an explicit `keep`.
                .policy(map.get("policy") == null ? null : parsePolicy(map.get("policy"), label))
                .build();
    }

    /**
     * Accepts the scalar shorthand ({@code policy: keep}) as well as the
     * full map. The shorthand exists so the common case does not have to
     * pay for the ceremony of an empty rule list.
     */
    @SuppressWarnings("unchecked")
    private static KitPolicyDto parsePolicy(@Nullable Object raw, String label) {
        if (raw == null) return KitPolicyDto.defaults();
        if (!(raw instanceof Map<?, ?> nested)) {
            return KitPolicyDto.builder()
                    .defaultAction(parseAction(raw.toString(), label + " policy"))
                    .build();
        }
        Map<String, Object> map = (Map<String, Object>) nested;
        KitPolicyAction defaultAction = map.get("default") == null
                ? KitPolicyAction.KEEP
                : parseAction(map.get("default").toString(), label + " policy.default");

        List<KitPolicyRuleDto> rules = new ArrayList<>();
        Object rulesRaw = map.get("rules");
        if (rulesRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String ruleLabel = label + " policy.rules[" + i + "]";
                if (!(list.get(i) instanceof Map<?, ?> ruleNested)) {
                    throw new KitException(ruleLabel + " must be a map");
                }
                Map<String, Object> e = (Map<String, Object>) ruleNested;
                String document = stringOrNull(e.get("document"));
                String setting = stringOrNull(e.get("setting"));
                if ((document == null) == (setting == null)) {
                    throw new KitException(ruleLabel
                            + " must name exactly one of 'document:' or 'setting:'"
                            + " — document paths and setting keys are separate namespaces."
                            + " Server-tool configs are documents:"
                            + " document: \"server-tools/*.yaml\"");
                }
                Object actionRaw = e.get("action");
                if (actionRaw == null) {
                    throw new KitException(ruleLabel + " is missing 'action'");
                }
                rules.add(KitPolicyRuleDto.builder()
                        .document(document)
                        .setting(setting)
                        .action(parseAction(actionRaw.toString(), ruleLabel + " action"))
                        .build());
            }
        } else if (rulesRaw != null) {
            throw new KitException(label + " policy.rules must be a list");
        }

        return KitPolicyDto.builder().defaultAction(defaultAction).rules(rules).build();
    }

    private static KitPolicyAction parseAction(String raw, String label) {
        try {
            return KitPolicyAction.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new KitException(label + ": unknown action '" + raw.trim()
                    + "' — expected one of keep, overwrite, ignore, merge");
        }
    }

    public static String writeConfig(KitConfigDto config) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (config.getSortIndex() != null) {
            root.put("sortIndex", config.getSortIndex());
        }
        if (config.getPolicy() != null) {
            root.put("policy", policyMap(config.getPolicy()));
        }
        return dump(root);
    }

    /**
     * Serialise a policy, collapsing to the scalar shorthand when there
     * are no exceptions — the common case should not pay for ceremony.
     */
    private static Object policyMap(KitPolicyDto policy) {
        String defaultAction = policy.getDefaultAction().name().toLowerCase(Locale.ROOT);
        if (policy.getRules() == null || policy.getRules().isEmpty()) {
            return defaultAction;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default", defaultAction);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (KitPolicyRuleDto r : policy.getRules()) {
            Map<String, Object> e = new LinkedHashMap<>();
            if (r.getDocument() != null) e.put("document", r.getDocument());
            if (r.getSetting() != null) e.put("setting", r.getSetting());
            e.put("action", r.getAction().name().toLowerCase(Locale.ROOT));
            rules.add(e);
        }
        out.put("rules", rules);
        return out;
    }

    // ──────────────────── kit.sig.yaml ────────────────────

    /** Parse a detached kit signature. Every field is required — a partial one verifies nothing. */
    public static KitSignatureDto parseSignature(String yamlText) {
        final String label = KitSignature.SIGNATURE_FILENAME;
        Map<String, Object> map = loadMap(yamlText, label);
        return KitSignatureDto.builder()
                .algorithm(requireString(map, "algorithm", label))
                .keyId(requireString(map, "keyId", label))
                .treeHash(requireString(map, "treeHash", label))
                .signedAt(parseInstant(map.get("signedAt")))
                .signature(requireString(map, "signature", label))
                .build();
    }

    public static String writeSignature(KitSignatureDto signature) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("algorithm", signature.getAlgorithm());
        root.put("keyId", signature.getKeyId());
        root.put("treeHash", signature.getTreeHash());
        if (signature.getSignedAt() != null) {
            root.put("signedAt", signature.getSignedAt().toString());
        }
        root.put("signature", signature.getSignature());
        return dump(root);
    }

    // ──────────────────── config/kit-sources.yaml ────────────────────

    /**
     * Parse the tenant's source configuration.
     *
     * <p>Strict on every field, because this document decides where a
     * tenant's kits may come from. A typo that silently degrades to
     * "unconfigured" would quietly turn a required signature into no
     * signature at all — so an unreadable entry is an error, not a
     * shrug.
     */
    @SuppressWarnings("unchecked")
    public static KitSourcesDto parseSources(String yamlText) {
        final String label = "kit-sources.yaml";
        Map<String, Object> map = loadMap(yamlText, label);

        List<KitSourceDto> sources = new ArrayList<>();
        Object raw = map.get("sources");
        if (raw == null) return KitSourcesDto.builder().sources(sources).build();
        if (!(raw instanceof List<?> list)) {
            throw new KitException(label + ": 'sources' must be a list");
        }
        Set<String> seenIds = new java.util.LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String entryLabel = label + " sources[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> nested)) {
                throw new KitException(entryLabel + " must be a map");
            }
            Map<String, Object> e = (Map<String, Object>) nested;
            String id = requireString(e, "id", entryLabel);
            if (!seenIds.add(id)) {
                throw new KitException(entryLabel + ": duplicate source id '" + id + "'");
            }
            KitSourceType type = parseSourceType(requireString(e, "type", entryLabel), entryLabel);
            sources.add(KitSourceDto.builder()
                    .id(id)
                    .type(type)
                    .url(requireString(e, "url", entryLabel))
                    .signature(e.get("signature") == null
                            ? KitSignaturePolicy.defaultFor(type)
                            : parseSignaturePolicy(e.get("signature"), entryLabel))
                    .publicKey(stringOrNull(e.get("publicKey")))
                    .storeUrl(stringOrNull(e.get("storeUrl")))
                    .title(stringOrNull(e.get("title")))
                    .build());
        }
        return KitSourcesDto.builder().sources(sources).build();
    }

    private static KitSourceType parseSourceType(String raw, String label) {
        try {
            return KitSourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new KitException(label + ": unknown source type '" + raw.trim()
                    + "' — expected one of git, folder, library");
        }
    }

    /**
     * Parse a signature policy, working around a YAML trap: in YAML 1.1
     * the bare word {@code off} is a <em>boolean</em>, so
     * {@code signature: off} arrives here as {@code false} and never as
     * the string anyone typed. Rejecting that would fail the most
     * obvious way to write the most common setting.
     *
     * <p>{@code true} is refused rather than guessed — {@code on} would
     * be its source, and "on" says nothing about whether signatures are
     * merely checked or actually required.
     */
    private static KitSignaturePolicy parseSignaturePolicy(Object raw, String label) {
        if (Boolean.FALSE.equals(raw)) return KitSignaturePolicy.OFF;
        if (Boolean.TRUE.equals(raw)) {
            throw new KitException(label + ": signature must say how strict it is — "
                    + "write 'required' or 'warn', not 'on'/'true'");
        }
        String value = raw.toString().trim();
        try {
            return KitSignaturePolicy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new KitException(label + ": unknown signature policy '" + value
                    + "' — expected one of off, warn, required");
        }
    }

    public static String writeSources(KitSourcesDto sources) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KitSourceDto s : sources.getSources()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", s.getId());
            e.put("type", s.getType().name().toLowerCase(Locale.ROOT));
            e.put("url", s.getUrl());
            e.put("signature", s.getSignature().name().toLowerCase(Locale.ROOT));
            if (s.getPublicKey() != null) e.put("publicKey", s.getPublicKey());
            out.add(e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sources", out);
        return dump(root);
    }

    // ──────────────────── kit-manifest.yaml ────────────────────

    public static KitManifestDto parseManifest(String yamlText) {
        Map<String, Object> map = loadMap(yamlText, "kit-manifest.yaml");

        Object kitRaw = map.get("kit");
        if (!(kitRaw instanceof Map<?, ?> kitMap)) {
            throw new KitException("kit-manifest.yaml must have a 'kit' map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> kitTyped = (Map<String, Object>) kitMap;
        KitMetadataDto metadata = KitMetadataDto.builder()
                .name(requireString(kitTyped, "name", "kit-manifest.yaml kit"))
                .description(requireString(kitTyped, "description", "kit-manifest.yaml kit"))
                .version(stringOrNull(kitTyped.get("version")))
                .build();

        Object originRaw = map.get("origin");
        if (!(originRaw instanceof Map<?, ?> originMap)) {
            throw new KitException("kit-manifest.yaml must have an 'origin' map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> originTyped = (Map<String, Object>) originMap;
        KitOriginDto origin = KitOriginDto.builder()
                .url(requireString(originTyped, "url", "kit-manifest.yaml origin"))
                .path(stringOrNull(originTyped.get("path")))
                .branch(stringOrNull(originTyped.get("branch")))
                .commit(stringOrNull(originTyped.get("commit")))
                .installedAt(parseInstant(originTyped.get("installedAt")))
                .installedBy(stringOrNull(originTyped.get("installedBy")))
                .build();

        List<KitInheritDto> inherits = new ArrayList<>();
        Object inheritsRaw = map.get("inherits");
        if (inheritsRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (!(element instanceof Map<?, ?> nested)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) nested;
                String url = stringOrNull(e.get("url"));
                if (url == null) continue;
                inherits.add(KitInheritDto.builder()
                        .url(url)
                        .path(stringOrNull(e.get("path")))
                        .branch(stringOrNull(e.get("branch")))
                        .commit(stringOrNull(e.get("commit")))
                        .build());
            }
        }

        List<InheritArtefactsDto> inheritArtefacts = new ArrayList<>();
        Object inheritArtefactsRaw = map.get("inheritArtefacts");
        if (inheritArtefactsRaw instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> nested)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) nested;
                String name = stringOrNull(e.get("name"));
                if (name == null) continue;
                inheritArtefacts.add(InheritArtefactsDto.builder()
                        .name(name)
                        .documents(stringList(e.get("documents")))
                        .settings(stringList(e.get("settings")))
                        .tools(stringList(e.get("tools")))
                        .build());
            }
        }

        return KitManifestDto.builder()
                .kit(metadata)
                .origin(origin)
                .documents(stringList(map.get("documents")))
                .settings(stringList(map.get("settings")))
                .tools(stringList(map.get("tools")))
                .inherits(inherits)
                .resolvedInherits(stringList(map.get("resolvedInherits")))
                .inheritArtefacts(inheritArtefacts)
                .hasEncryptedSecrets(booleanOrFalse(map.get("hasEncryptedSecrets")))
                .build();
    }

    public static String writeManifest(KitManifestDto manifest) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> kit = new LinkedHashMap<>();
        kit.put("name", manifest.getKit().getName());
        kit.put("description", manifest.getKit().getDescription());
        if (manifest.getKit().getVersion() != null) {
            kit.put("version", manifest.getKit().getVersion());
        }
        root.put("kit", kit);

        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("url", manifest.getOrigin().getUrl());
        if (manifest.getOrigin().getPath() != null) origin.put("path", manifest.getOrigin().getPath());
        if (manifest.getOrigin().getBranch() != null) origin.put("branch", manifest.getOrigin().getBranch());
        if (manifest.getOrigin().getCommit() != null) origin.put("commit", manifest.getOrigin().getCommit());
        if (manifest.getOrigin().getInstalledAt() != null) {
            origin.put("installedAt", manifest.getOrigin().getInstalledAt().toString());
        }
        if (manifest.getOrigin().getInstalledBy() != null) {
            origin.put("installedBy", manifest.getOrigin().getInstalledBy());
        }
        root.put("origin", origin);

        if (manifest.getDocuments() != null && !manifest.getDocuments().isEmpty()) {
            root.put("documents", new ArrayList<>(manifest.getDocuments()));
        }
        if (manifest.getSettings() != null && !manifest.getSettings().isEmpty()) {
            root.put("settings", new ArrayList<>(manifest.getSettings()));
        }
        if (manifest.getTools() != null && !manifest.getTools().isEmpty()) {
            root.put("tools", new ArrayList<>(manifest.getTools()));
        }
        if (manifest.getInherits() != null && !manifest.getInherits().isEmpty()) {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (KitInheritDto i : manifest.getInherits()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("url", i.getUrl());
                if (i.getPath() != null) e.put("path", i.getPath());
                if (i.getBranch() != null) e.put("branch", i.getBranch());
                if (i.getCommit() != null) e.put("commit", i.getCommit());
                serialized.add(e);
            }
            root.put("inherits", serialized);
        }
        if (manifest.getResolvedInherits() != null && !manifest.getResolvedInherits().isEmpty()) {
            root.put("resolvedInherits", new ArrayList<>(manifest.getResolvedInherits()));
        }
        if (manifest.getInheritArtefacts() != null && !manifest.getInheritArtefacts().isEmpty()) {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (InheritArtefactsDto i : manifest.getInheritArtefacts()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", i.getName());
                if (i.getDocuments() != null && !i.getDocuments().isEmpty()) {
                    e.put("documents", new ArrayList<>(i.getDocuments()));
                }
                if (i.getSettings() != null && !i.getSettings().isEmpty()) {
                    e.put("settings", new ArrayList<>(i.getSettings()));
                }
                if (i.getTools() != null && !i.getTools().isEmpty()) {
                    e.put("tools", new ArrayList<>(i.getTools()));
                }
                serialized.add(e);
            }
            root.put("inheritArtefacts", serialized);
        }
        if (manifest.isHasEncryptedSecrets()) {
            root.put("hasEncryptedSecrets", true);
        }
        return dump(root);
    }

    // ──────────────────── settings/<key>.yaml ────────────────────

    public record ParsedSetting(SettingType type, @Nullable String value, @Nullable String description) {}

    // ──────────────────── template.yaml ────────────────────

    /**
     * Parse the {@code template.yaml} sibling of a kit's {@code kit.yaml}.
     * Tool-template kits carry this extra manifest to declare which
     * inputs the apply step needs — see
     * {@link TemplateDescriptor}.
     *
     * @param yamlText file content
     * @return parsed descriptor; never null
     * @throws KitException on schema violation (missing name, bad type, …)
     */
    @SuppressWarnings("unchecked")
    public static TemplateDescriptor parseTemplate(String yamlText) {
        Map<String, Object> map = loadMap(yamlText, "template.yaml");
        String name = requireString(map, "name", "template.yaml");
        String title = stringOrNull(map.get("title"));
        String description = stringOrNull(map.get("description"));
        String icon = stringOrNull(map.get("icon"));

        List<TemplateInput> inputs = new ArrayList<>();
        Object inputsRaw = map.get("inputs");
        if (inputsRaw instanceof List<?> list) {
            int idx = 0;
            for (Object e : list) {
                if (!(e instanceof Map<?, ?> m)) {
                    throw new KitException(
                            "template.yaml: inputs[" + idx + "] must be a map");
                }
                try {
                    inputs.add(parseInput((Map<String, Object>) m));
                } catch (IllegalArgumentException ex) {
                    throw new KitException("template.yaml: " + ex.getMessage(), ex);
                }
                idx++;
            }
        }
        // Reject duplicate input names — would silently win/lose in the
        // substitution map and is always a config bug.
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (TemplateInput i : inputs) {
            if (!seen.add(i.name())) {
                throw new KitException(
                        "template.yaml: duplicate input name '" + i.name() + "'");
            }
        }

        TemplatePostInstall postInstall = null;
        Object piRaw = map.get("postInstall");
        if (piRaw instanceof Map<?, ?> piMap) {
            try {
                postInstall = parsePostInstall((Map<String, Object>) piMap);
            } catch (IllegalArgumentException ex) {
                throw new KitException("template.yaml: " + ex.getMessage(), ex);
            }
        }

        List<TemplateDerived> derived = parseDerived(map.get("derived"), inputs);
        List<TemplateDocumentOverlay> documents = parseDocumentsOverlay(
                map.get("documents"), inputs);

        return new TemplateDescriptor(name, title, description, icon,
                List.copyOf(inputs), derived, documents, postInstall);
    }

    @SuppressWarnings("unchecked")
    private static List<TemplateDerived> parseDerived(
            @Nullable Object raw, List<TemplateInput> inputs) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new KitException("template.yaml: 'derived' must be a list");
        }
        // Build a lookup of multi-select input names + their choice values
        // so we can validate `from` and `perChoice` keys at parse time.
        Map<String, java.util.Set<String>> multiSelectChoices = new LinkedHashMap<>();
        for (TemplateInput in : inputs) {
            if (in.type() == TemplateInputType.MULTI_SELECT) {
                multiSelectChoices.put(in.name(),
                        new java.util.LinkedHashSet<>(in.choiceValues()));
            }
        }
        List<TemplateDerived> out = new ArrayList<>();
        java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
        int idx = 0;
        for (Object el : list) {
            if (!(el instanceof Map<?, ?> mm)) {
                throw new KitException("template.yaml: derived[" + idx + "] must be a map");
            }
            Map<String, Object> mp = (Map<String, Object>) mm;
            try {
                TemplateDerived d = parseDerivedOne(mp, multiSelectChoices);
                if (!seenNames.add(d.name())) {
                    throw new IllegalArgumentException(
                            "duplicate derived name '" + d.name() + "'");
                }
                // Also forbid name collisions with inputs (would mask the input).
                for (TemplateInput in : inputs) {
                    if (in.name().equals(d.name())) {
                        throw new IllegalArgumentException(
                                "derived '" + d.name() + "' shadows input of the same name");
                    }
                }
                out.add(d);
            } catch (IllegalArgumentException e) {
                throw new KitException("template.yaml: derived[" + idx + "]: " + e.getMessage(), e);
            }
            idx++;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static TemplateDerived parseDerivedOne(
            Map<String, Object> mp,
            Map<String, java.util.Set<String>> multiSelectChoices) {
        String name = stringOrNull(mp.get("name"));
        if (name == null) {
            throw new IllegalArgumentException("'name' is required");
        }
        TemplateDerived.Kind kind = TemplateDerived.Kind.parse(
                stringOrNull(mp.get("kind")), name);
        String from = stringOrNull(mp.get("from"));
        if (from == null) {
            throw new IllegalArgumentException(
                    "derived '" + name + "': 'from' (multi-select input name) is required");
        }
        java.util.Set<String> allowedChoices = multiSelectChoices.get(from);
        if (allowedChoices == null) {
            throw new IllegalArgumentException(
                    "derived '" + name + "': 'from' must reference a multi-select input "
                            + "(known multi-select inputs: " + multiSelectChoices.keySet() + ")");
        }
        List<String> base = stringList(mp.get("base"));
        Map<String, List<String>> perChoice = new LinkedHashMap<>();
        Object perChoiceRaw = mp.get("perChoice");
        if (perChoiceRaw != null) {
            if (!(perChoiceRaw instanceof Map<?, ?> pm)) {
                throw new IllegalArgumentException(
                        "derived '" + name + "': perChoice must be a map");
            }
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                String key = e.getKey() == null ? null : e.getKey().toString();
                if (key == null) continue;
                if (!allowedChoices.contains(key)) {
                    throw new IllegalArgumentException(
                            "derived '" + name + "': perChoice key '" + key
                                    + "' is not a value of input '" + from
                                    + "' (allowed: " + allowedChoices + ")");
                }
                perChoice.put(key, stringList(e.getValue()));
            }
        }
        return new TemplateDerived(name, kind, from, base, perChoice);
    }

    @SuppressWarnings("unchecked")
    private static List<TemplateDocumentOverlay> parseDocumentsOverlay(
            @Nullable Object raw, List<TemplateInput> inputs) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new KitException("template.yaml: 'documents' must be a list");
        }
        // Collect all known multi-select choice values across all multi-select
        // inputs (we accept overlay-`requires` referring to any of them).
        java.util.Set<String> knownChoices = new java.util.LinkedHashSet<>();
        for (TemplateInput in : inputs) {
            if (in.type() == TemplateInputType.MULTI_SELECT) {
                knownChoices.addAll(in.choiceValues());
            }
        }
        List<TemplateDocumentOverlay> out = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.LinkedHashSet<>();
        int idx = 0;
        for (Object el : list) {
            if (!(el instanceof Map<?, ?> mm)) {
                throw new KitException("template.yaml: documents[" + idx + "] must be a map");
            }
            Map<String, Object> mp = (Map<String, Object>) mm;
            try {
                String path = stringOrNull(mp.get("path"));
                if (path == null) {
                    throw new IllegalArgumentException("'path' is required");
                }
                if (!seenPaths.add(path)) {
                    throw new IllegalArgumentException("duplicate path '" + path + "'");
                }
                List<String> requires = stringOrList(mp.get("requires"));
                if (knownChoices.isEmpty()) {
                    throw new IllegalArgumentException(
                            "documents overlay on '" + path + "': requires a multi-select input "
                                    + "(none declared in template.yaml)");
                }
                for (String r : requires) {
                    if (!knownChoices.contains(r)) {
                        throw new IllegalArgumentException(
                                "documents '" + path + "': requires '" + r
                                        + "' is not a known multi-select choice value "
                                        + "(known: " + knownChoices + ")");
                    }
                }
                out.add(new TemplateDocumentOverlay(path, requires));
            } catch (IllegalArgumentException e) {
                throw new KitException("template.yaml: documents[" + idx + "]: " + e.getMessage(), e);
            }
            idx++;
        }
        return out;
    }

    /**
     * Accepts either a single string or a list of strings — used for
     * {@code documents.requires:} which is naturally one-or-many.
     */
    private static List<String> stringOrList(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?>) return stringList(raw);
        return List.of(raw.toString());
    }

    @SuppressWarnings("unchecked")
    private static TemplateInput parseInput(Map<String, Object> m) {
        String inputName = stringOrNull(m.get("name"));
        if (inputName == null) {
            throw new IllegalArgumentException("input: 'name' is required");
        }
        TemplateInputType type = TemplateInputType.parse(
                stringOrNull(m.get("type")), inputName);
        String label = stringOrNull(m.get("label"));
        String help = stringOrNull(m.get("help"));
        boolean required = booleanOr(m.get("required"), true);
        String defaultValue = stringOrNull(m.get("default"));
        List<TemplateChoice> choices = parseChoices(m.get("choices"), inputName);
        TemplateInputTarget target = parseTarget(m.get("target"), inputName);
        return new TemplateInput(
                inputName, type, label, help, required,
                defaultValue, choices, target);
    }

    /**
     * Accepts both the v1 flat string-list shape ({@code [a, b, c]}) and
     * the v2 richer map-list shape
     * ({@code [{value: a, label: A, default: true}, …]}).
     *
     * <p>For multi-select, per-choice defaults can only be expressed via
     * the map form — the flat form is allowed for compatibility but
     * defaults to {@code default=false} per choice. The string form is
     * deliberately permitted so existing v1 single-select templates keep
     * parsing unchanged.
     */
    @SuppressWarnings("unchecked")
    private static List<TemplateChoice> parseChoices(@Nullable Object raw, String inputName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': choices must be a list");
        }
        List<TemplateChoice> out = new ArrayList<>(list.size());
        int idx = 0;
        for (Object el : list) {
            if (el == null) {
                throw new IllegalArgumentException(
                        "input '" + inputName + "': choices[" + idx + "] is null");
            }
            if (el instanceof Map<?, ?> mm) {
                Map<String, Object> mp = (Map<String, Object>) mm;
                String value = stringOrNull(mp.get("value"));
                if (value == null) {
                    throw new IllegalArgumentException(
                            "input '" + inputName + "': choices[" + idx
                                    + "]: 'value' is required");
                }
                String label = stringOrNull(mp.get("label"));
                boolean dflt = booleanOr(mp.get("default"), false);
                out.add(new TemplateChoice(value, label, dflt));
            } else {
                out.add(new TemplateChoice(el.toString(), null, false));
            }
            idx++;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static TemplateInputTarget parseTarget(@Nullable Object raw, String inputName) {
        if (raw == null) return TemplateInputTarget.documentInline();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': target must be a map");
        }
        Map<String, Object> tm = (Map<String, Object>) m;
        String kindRaw = stringOrNull(tm.get("kind"));
        if (kindRaw == null || "document-inline".equalsIgnoreCase(kindRaw)
                || "inline".equalsIgnoreCase(kindRaw)) {
            return TemplateInputTarget.documentInline();
        }
        if (!"setting".equalsIgnoreCase(kindRaw)) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': target.kind must be 'setting' or 'document-inline'");
        }
        TemplateInputTarget.Scope scope = TemplateInputTarget.Scope.parse(
                stringOrNull(tm.get("scope")), inputName);
        String project = stringOrNull(tm.get("project"));
        String key = stringOrNull(tm.get("key"));
        if (key == null) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': target.key is required for kind=setting");
        }
        if (scope == TemplateInputTarget.Scope.PROJECT && project == null) {
            // project=null means "apply to the project the kit is being
            // applied to" — explicit choice, validated at apply time.
        }
        return new TemplateInputTarget(
                TemplateInputTarget.Kind.SETTING, scope, project, key);
    }

    private static TemplatePostInstall parsePostInstall(Map<String, Object> m) {
        TemplatePostInstall.Kind kind = TemplatePostInstall.Kind.parse(
                stringOrNull(m.get("kind")));
        String provider = stringOrNull(m.get("provider"));
        String message = stringOrNull(m.get("message"));
        if (kind == TemplatePostInstall.Kind.OAUTH_CONNECT && provider == null) {
            throw new IllegalArgumentException(
                    "postInstall (oauth-connect): 'provider' is required");
        }
        return new TemplatePostInstall(kind, provider, message);
    }

    // ──────────────────── settings/*.yaml ────────────────────

    public static ParsedSetting parseSetting(String yamlText, String filename) {
        Map<String, Object> map = loadMap(yamlText, filename);
        String typeRaw = requireString(map, "type", filename);
        SettingType type;
        try {
            type = SettingType.valueOf(typeRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new KitException(filename + ": unknown setting type '" + typeRaw + "'");
        }
        Object valueRaw = map.get("value");
        String value = valueRaw == null ? null : valueRaw.toString();
        return new ParsedSetting(type, value, stringOrNull(map.get("description")));
    }

    public static String writeSetting(ParsedSetting setting) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", setting.type().name());
        if (setting.value() != null) root.put("value", setting.value());
        if (setting.description() != null) root.put("description", setting.description());
        return dump(root);
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMap(String yamlText, String label) {
        Yaml yaml = new Yaml();
        Object parsed;
        try {
            parsed = yaml.load(yamlText);
        } catch (RuntimeException e) {
            throw new KitException("Failed to parse " + label + ": " + e.getMessage(), e);
        }
        if (parsed == null) {
            return new LinkedHashMap<>();
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new KitException(label + " must be a top-level map");
        }
        return (Map<String, Object>) m;
    }

    private static String dump(Map<String, Object> map) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(map);
    }

    private static String requireString(Map<String, Object> map, String key, String label) {
        Object v = map.get(key);
        if (v == null) {
            throw new KitException(label + ": missing required field '" + key + "'");
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            throw new KitException(label + ": field '" + key + "' must not be blank");
        }
        return s;
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean booleanOrFalse(@Nullable Object v) {
        return booleanOr(v, false);
    }

    private static boolean booleanOr(@Nullable Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static List<String> stringList(@Nullable Object v) {
        if (v == null) return new ArrayList<>();
        if (!(v instanceof List<?> list)) {
            throw new KitException("expected a list, got " + v.getClass().getSimpleName());
        }
        List<String> result = new ArrayList<>();
        for (Object e : list) {
            if (e == null) continue;
            String s = e.toString().trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private static @Nullable Instant parseInstant(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.util.Date d) return d.toInstant();
        try {
            return Instant.parse(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
