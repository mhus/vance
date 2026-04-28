package de.mhus.vance.tools.generatej2ts;

import de.mhus.vance.tools.generatej2ts.model.JavaClassModel;
import de.mhus.vance.tools.generatej2ts.model.JavaFieldModel;
import de.mhus.vance.tools.generatej2ts.model.JavaKind;
import de.mhus.vance.tools.generatej2ts.ts.TypeScriptField;
import de.mhus.vance.tools.generatej2ts.ts.TypeScriptKind;
import de.mhus.vance.tools.generatej2ts.ts.TypeScriptModel;
import de.mhus.vance.tools.generatej2ts.ts.TypeScriptType;
import org.apache.maven.plugin.logging.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Erzeugt ein TypeScriptModel aus einer Liste von JavaClassModel.
 * Minimalversion mit einfacher Typabbildung und Beachtung von Annotation-Overrides.
 */
public class TypeScriptGenerator {

    private final Log log;
    private final Configuration configuration;

    public TypeScriptGenerator(Log log) {
        this(log, null);
    }

    public TypeScriptGenerator(Log log, Configuration configuration) {
        this.log = log;
        this.configuration = configuration;
    }

    public TypeScriptModel generate(List<JavaClassModel> javaModels) {
        TypeScriptModel tsModel = new TypeScriptModel();
        if (javaModels == null) return tsModel;

        // Index für Follow-Imports: SimpleName -> JavaClassModel
        java.util.Map<String, JavaClassModel> bySimpleName = new java.util.HashMap<>();
        for (JavaClassModel jm : javaModels) {
            if (jm != null && jm.getName() != null) {
                bySimpleName.putIfAbsent(jm.getName(), jm);
            }
        }

        for (JavaClassModel jm : javaModels) {
            if (jm == null) continue;
            TypeScriptType tt = new TypeScriptType();
            String overrideName = jm.getGenerateInterfaceName();
            if (overrideName != null && !overrideName.isBlank()) {
                tt.setName(overrideName.trim());
            } else {
                tt.setName(jm.getName());
            }
            tt.setSubfolder(jm.getGenerateSubfolder());
            tt.setFileName(jm.getGenerateFileName());
            // vollqualifizierten Java-Quellklassennamen setzen (für Header-Kommentar)
            String pkg = jm.getPackageName();
            if (pkg != null && !pkg.isBlank()) {
                tt.setSourceFqn(pkg + "." + jm.getName());
            } else {
                tt.setSourceFqn(jm.getName());
            }
            // Übernehme Import-Zeilen
            tt.getImports().addAll(jm.getTypeScriptImports());

            if (jm.getKind() == JavaKind.ENUM) {
                tt.setKind(TypeScriptKind.ENUM);
                tt.getEnumValues().addAll(jm.getEnumConstants());
            } else {
                tt.setKind(TypeScriptKind.INTERFACE);
                // `followed` drives inner-enum inlining and is gated on the
                // explicit @TypeScript(follow=true) marker on a field.
                // `referenced` is broader: it collects every cross-class type
                // reference and is used to emit auto-imports — that runs
                // unconditionally so each generated .ts file is self-contained
                // for tooling that type-checks files in isolation.
                List<String> followed = new ArrayList<>();
                List<String> referenced = new ArrayList<>();
                for (JavaFieldModel f : jm.getFields()) {
                    if (f == null) continue;
                    if (f.isIgnored()) continue;
                    // static final Felder → als TS-Konstanten exportieren (nicht im Interface)
                    if (f.isStaticFinal()) {
                        String tsType = resolveTsType(f);
                        String value = f.getInitializer();
                        de.mhus.vance.tools.generatej2ts.ts.TypeScriptConstant c =
                                new de.mhus.vance.tools.generatej2ts.ts.TypeScriptConstant(f.getName(), tsType, value);
                        tt.getConstants().add(c);
                        continue;
                    }
                    String tsType = resolveTsType(f);
                    boolean optional = f.isOptional();
                    TypeScriptField tf = new TypeScriptField(f.getName(), tsType, optional);
                    tf.setDescription(f.getDescription());
                    tt.getFields().add(tf);

                    // Auto-import scanner: read identifiers off the *resolved
                    // TS type*, not the raw Java type — `Map<Criticality, …>`
                    // becomes `Record<string, …>` so `Criticality` no longer
                    // appears and we must not generate a phantom import.
                    for (String r : extractTsTypeReferences(tsType)) {
                        if (!referenced.contains(r)) referenced.add(r);
                    }
                    if (f.isFollow()) {
                        for (String r : f.getReferencedTypes()) {
                            if (r != null && !r.isBlank() && !followed.contains(r)) followed.add(r);
                        }
                    }
                }
                // Feld-Imports einsammeln und in Header übernehmen
                for (JavaFieldModel f : jm.getFields()) {
                    if (f == null || f.isIgnored()) continue;
                    String impLine = buildFieldImportLine(f);
                    if (impLine != null && !impLine.isBlank()) {
                        tt.getImports().add(ensureSemicolon(impLine.trim()));
                    }
                }
                // Inner Enums aus dem Java-Modell übernehmen, wenn per follow referenziert
                if (!followed.isEmpty() && jm.getInnerEnums() != null) {
                    for (var em : jm.getInnerEnums()) {
                        if (em == null) continue;
                        if (followed.contains(em.getName())) {
                            var ne = new de.mhus.vance.tools.generatej2ts.ts.TypeScriptNestedEnum();
                            ne.setName(em.getName());
                            ne.getValues().addAll(em.getConstants());
                            tt.getNestedEnums().add(ne);
                        }
                    }
                }
                // Default-Imports aus Konfiguration hinzufügen (nur für Interfaces)
                addDefaultImports(tt);

                // Auto-imports for every cross-class type reference that is
                // actually present in the model. Types that aren't in the
                // model (foreign types like JsonNode) are silently skipped —
                // the user is expected to add a @TypeScriptImport for those.
                for (String name : referenced) {
                    if (name.equals(tt.getName())) continue; // self-reference
                    JavaClassModel target = bySimpleName.get(name);
                    if (target == null) continue;
                    boolean isNestedEnum = tt.getNestedEnums().stream()
                            .anyMatch(ne -> ne.getName().equals(name));
                    if (isNestedEnum) continue;
                    String importSymbol = resolveTargetTsName(target);
                    String relPath = buildRelativeImportPath(tt.getSubfolder(), target.getGenerateSubfolder(),
                            resolveTargetBaseFileName(target, importSymbol));
                    String line = "import { " + importSymbol + " } from '" + relPath + "'";
                    tt.getImports().add(ensureSemicolon(line));
                }
            }

            tsModel.getTypes().add(tt);
        }

        if (log != null) log.info("TypeScriptGenerator: erzeugte Typen: " + tsModel.getTypes().size());
        return tsModel;
    }

    private String resolveTargetTsName(JavaClassModel jm) {
        String overrideName = jm.getGenerateInterfaceName();
        if (overrideName != null && !overrideName.isBlank()) return overrideName.trim();
        return jm.getName();
    }

    private String resolveTargetBaseFileName(JavaClassModel jm, String tsName) {
        String file = jm.getGenerateFileName();
        if (file != null && !file.isBlank()) {
            if (file.endsWith(".ts")) return file.substring(0, file.length() - 3);
            return file;
        }
        return tsName;
    }

    private String buildRelativeImportPath(String fromSub, String toSub, String baseName) {
        // Normalisiere
        String from = (fromSub == null || fromSub.isBlank()) ? "" : fromSub;
        String to = (toSub == null || toSub.isBlank()) ? "" : toSub;
        if (from.equals(to)) {
            return (from.isEmpty() ? "" : "./") + baseName;
        }
        if (from.isEmpty()) {
            // von Root zu Unterordner
            return (to.isEmpty() ? "" : to + "/") + baseName;
        }
        // von Unterordner zu Root oder anderem Unterordner
        String[] fromParts = from.split("/");
        String[] toParts = to.isEmpty() ? new String[0] : to.split("/");
        // Finde gemeinsamen Präfix
        int i = 0;
        while (i < fromParts.length && i < toParts.length && fromParts[i].equals(toParts[i])) i++;
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < fromParts.length; j++) sb.append("../");
        for (int j = i; j < toParts.length; j++) {
            if (sb.length() > 0 && sb.charAt(sb.length()-1) != '/') sb.append('/');
            sb.append(toParts[j]);
        }
        if (sb.length() > 0 && sb.charAt(sb.length()-1) != '/') sb.append('/');
        sb.append(baseName);
        return sb.toString();
    }

    private String ensureSemicolon(String s) {
        if (s.endsWith(";")) return s;
        return s + ";";
    }

    private String buildFieldImportLine(JavaFieldModel f) {
        // 1) Vollständige Import-Zeile direkt verwenden, falls vorhanden
        String line = f.getInlineImportLine();
        if (line != null && !line.isBlank()) return line;
        // 2) Aus strukturierter Angabe konstruieren
        String symbol = safe(f.getImportSymbol());
        String path = safe(f.getImportPath());
        String alias = safe(f.getImportAs());
        if (!symbol.isEmpty() && !path.isEmpty()) {
            String spec = symbol + (alias.isEmpty() ? "" : (" as " + alias));
            return "import { " + spec + " } from '" + path + "'";
        }
        // 3) Backward-Compat: importOverride könnte eine komplette Zeile sein
        String legacy = f.getImportOverride();
        if (legacy != null && !legacy.isBlank()) {
            String trimmed = legacy.trim();
            if (trimmed.startsWith("import ")) return trimmed;
            // Falls nur Symbol angegeben wurde und Pfad separat existiert
            if (!symbol.isEmpty() && !path.isEmpty()) {
                String spec = symbol + (alias.isEmpty() ? "" : (" as " + alias));
                return "import { " + spec + " } from '" + path + "'";
            }
        }
        return null;
    }

    private String safe(String s) { return s == null ? "" : s.trim(); }

    private void addDefaultImports(TypeScriptType tt) {
        if (configuration == null) return;
        List<String> defs;
        try {
            defs = configuration.getDefaultImports();
        } catch (Exception e) {
            return;
        }
        if (defs == null || defs.isEmpty()) return;

        String sub = tt.getSubfolder();
        for (String imp : defs) {
            String adj = adjustImportForDepth(imp, sub);
            if (adj != null && !adj.isBlank()) tt.getImports().add(adj);
        }
    }

    /**
     * Passt den in einem Import-Statement enthaltenen Pfad anhand der Subfolder-Tiefe an.
     * Regel: Wenn der Pfad ein relativer Pfad ist (kein Alias mit '@' und nicht bereits mit '.' beginnend),
     * wird '../' pro Verzeichnistiefe (Anzahl der Segmente in subfolder) vorangestellt.
     */
    private String adjustImportForDepth(String importLine, String subfolder) {
        if (importLine == null || importLine.isBlank()) return importLine;
        int depth = 0;
        if (subfolder != null && !subfolder.isBlank()) {
            // Anzahl Segmente zählen
            String s = subfolder;
            if (s.startsWith("/")) s = s.substring(1);
            if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (!s.isBlank()) depth = (int) s.chars().filter(ch -> ch == '/').count() + 1;
        }
        if (depth <= 0) return importLine;

        // Nur den from-Teil ohne abschließendes Semikolon matchen, damit dieses erhalten bleibt
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("from\\s+(['\\\"])([^'\\\"]+)(['\\\"])\\s*");
        java.util.regex.Matcher m = p.matcher(importLine);
        if (!m.find()) return importLine; // kein Standard-Pattern -> nicht verändern

        String quote1 = m.group(1);
        String path = m.group(2);
        String quote2 = m.group(3);

        // Alias/absolute Pfade nicht verändern
        if (path.startsWith("@") || path.startsWith("/")) return importLine;
        // Bereits relativ mit '.' -> unverändert lassen
        if (path.startsWith(".")) return importLine;

        StringBuilder pref = new StringBuilder();
        for (int i = 0; i < depth; i++) pref.append("../");
        String newPath = pref + path;
        String replacement = "from " + quote1 + newPath + quote2;
        return m.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    private String resolveTsType(JavaFieldModel f) {
        // Vorrang: expliziter TS-Typ aus Annotation
        if (f.getTsTypeOverride() != null && !f.getTsTypeOverride().isBlank()) {
            return f.getTsTypeOverride();
        }
        String raw = Objects.toString(f.getJavaType(), "");
        if (raw.isBlank()) return "any";

        // Normalisieren (ohne Whitespaces)
        String s = raw.replace("\n"," ").replaceAll("\\s+", " ").trim();

        // Arrays: X[] → X[] (Type extrahieren)
        if (s.endsWith("[]")) {
            String base = s.substring(0, s.length() - 2).trim();
            return mapSimple(base) + "[]";
        }

        // Generics behandeln: z.B. List<Foo>, Set<Bar>, Optional<T>, Map<K,V>
        int lt = s.indexOf('<');
        int gt = s.lastIndexOf('>');
        if (lt > 0 && gt > lt) {
            String rawType = s.substring(0, lt).trim();
            String generics = s.substring(lt + 1, gt).trim();
            String[] parts = splitTopLevel(generics);

            String rt = rawType;
            String rtLow = rt.toLowerCase(Locale.ROOT);

            if (rtLow.endsWith("list") || rtLow.endsWith("set") || rtLow.endsWith("collection")) {
                String elem = parts.length > 0 ? parts[0].trim() : "any";
                return mapSimple(elem) + "[]";
            }
            if (rtLow.endsWith("optional")) {
                String elem = parts.length > 0 ? parts[0].trim() : "any";
                // optionales Feld ist bereits durch f.isOptional() modelliert;
                // in TS-Typ bleibt nur der Elementtyp
                return mapSimple(elem);
            }
            if (rtLow.endsWith("map")) {
                // Map<K,V> → Record<string, V>
                String v = parts.length > 1 ? parts[1].trim() : "any";
                return "Record<string, " + mapSimple(v) + ">";
            }
            // Default: generics entfernen und Simple‑Typ mappen
            String main = rawType.trim();
            return mapSimple(main);
        }

        // Kein Generic/Array, einfacher Typ
        return mapSimple(s);
    }

    private String mapSimple(String javaTypeName) {
        if (javaTypeName == null || javaTypeName.isBlank()) return "any";
        String t = javaTypeName.trim();

        // Entferne vollqualifizierte Namen → SimpleName
        int lastDot = t.lastIndexOf('.');
        String simple = (lastDot >= 0) ? t.substring(lastDot + 1) : t;

        // Konfigurations-Mapping prüfen (exakter String und SimpleName)
        String cfg = mapByConfiguration(javaTypeName, simple);
        if (cfg != null) return cfg;

        // Immer gültiges Default-Mapping, auch ohne Konfiguration
        if ("Instant".equals(simple) || "java.time.Instant".equals(t)) {
            return "Date";
        }

        // Entferne generische Reste (Sicherheitsnetz)
        int lt = simple.indexOf('<');
        if (lt >= 0) simple = simple.substring(0, lt).trim();

        switch (simple) {
            case "byte":
            case "short":
            case "int":
            case "long":
            case "float":
            case "double":
            case "Byte":
            case "Short":
            case "Integer":
            case "Long":
            case "Float":
            case "Double":
                return "number";
            case "boolean":
            case "Boolean":
                return "boolean";
            case "char":
            case "Character":
            case "String":
                return "string";
            case "Object":
                return "any";
            default:
                // Unbekannter/benutzerdefinierter Typ → identisch übernehmen (wird als eigener TS‑Typ generiert)
                return simple;
        }
    }

    private String mapByConfiguration(String original, String simple) {
        if (configuration == null) return null;
        try {
            java.util.Map<String,String> mappings = configuration.getTypeMappings();
            if (mappings == null || mappings.isEmpty()) return null;
            if (original != null) {
                String hit = mappings.get(original.trim());
                if (hit != null && !hit.isBlank()) return hit;
            }
            String hit = mappings.get(simple);
            if (hit != null && !hit.isBlank()) return hit;
        } catch (Exception ignore) {
            // Konfigurationsprobleme sollen die Generierung nicht abbrechen
        }
        return null;
    }

    /**
     * Pull the user-defined type identifiers out of a resolved TypeScript type
     * string. Strips Array suffixes, generic punctuation, and TypeScript
     * builtins so only references that need an {@code import} remain.
     */
    static java.util.Set<String> extractTsTypeReferences(String tsType) {
        java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
        if (tsType == null || tsType.isBlank()) return refs;

        String s = tsType.replace("[]", " ");
        s = s.replace('<', ' ').replace('>', ' ').replace(',', ' ').replace('|', ' ').replace('&', ' ');

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(s);
        while (m.find()) {
            String id = m.group();
            if (TS_BUILTINS.contains(id)) continue;
            // user types are conventionally PascalCase — primitives like
            // `string`/`number` are already filtered via TS_BUILTINS, but the
            // case check guards against quirky lowercase names sneaking in.
            if (!Character.isUpperCase(id.charAt(0))) continue;
            refs.add(id);
        }
        return refs;
    }

    /** TypeScript builtins that never need an import. */
    private static final java.util.Set<String> TS_BUILTINS = java.util.Set.of(
            "string", "number", "boolean", "any", "unknown", "void", "null", "undefined", "never",
            "Date", "Array", "ReadonlyArray", "Record", "Map", "Set", "Promise", "Partial",
            "Required", "Pick", "Omit", "Exclude", "Extract", "NonNullable", "ReturnType",
            "InstanceType", "Awaited", "Readonly", "Object"
    );

    private static String[] splitTopLevel(String s) {
        // Teilt Generic-Argumente auf oberster Ebene, z. B. "Map<String, List<Foo>>" → ["String", "List<Foo>"]
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') { depth++; current.append(c); continue; }
            if (c == '>') { depth--; current.append(c); continue; }
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) parts.add(current.toString().trim());
        return parts.toArray(new String[0]);
    }
}
