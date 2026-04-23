package de.mhus.vance.tools.generatej2ts;

import org.apache.maven.plugin.logging.Log;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Minimale Konfigurationsklasse für das Java→TS-Plugin.
 *
 * Aktuell werden die Daten generisch als Map geladen. Die Struktur kann
 * später mit echten Feldern (z. B. include/exclude-Regeln, Typ-Mappings etc.)
 * erweitert werden.
 */
public class Configuration {

    private final Map<String, Object> raw;

    public Configuration(Map<String, Object> raw) {
        this.raw = raw == null ? Collections.emptyMap() : raw;
    }

    public static Configuration loadIfExists(File yamlFile, Log log) throws IOException {
        if (yamlFile == null) return null;
        if (!yamlFile.exists()) {
            if (log != null) log.info("Keine Konfiguration gefunden (optional): " + yamlFile.getAbsolutePath());
            return null;
        }
        try (FileInputStream fis = new FileInputStream(yamlFile)) {
            Yaml yaml = new Yaml();
            Object data = yaml.load(fis);
            Map<String, Object> map = (data instanceof Map) ? (Map<String, Object>) data : Collections.emptyMap();
            if (log != null) log.info("Konfiguration geladen aus: " + yamlFile.getAbsolutePath());
            return new Configuration(map);
        }
    }

    /**
     * Liefert eine grobe Anzahl an Regeln/Einträgen in der rohen Map zurück.
     * Dient nur der Information im Log.
     */
    public int countRules() {
        return raw.size();
    }

    public Map<String, Object> getRaw() {
        return raw;
    }

    /**
     * Liefert Typ-Mappings Java→TypeScript aus der Konfiguration.
     * Unterstützte YAML-Formate:
     *
     * typeMappings:
     *   java.time.Instant: Date
     *   Instant: Date
     *
     * oder
     *
     * typeMappings:
     *   - java: java.time.Instant
     *     ts: Date
     *   - java: Instant
     *     ts: Date
     *
     * Zusätzlich wird immer das Default-Mapping "Instant"→"Date" bereitgestellt,
     * falls es nicht bereits konfiguriert wurde.
     */
    public Map<String, String> getTypeMappings() {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();

        Object tm = raw.get("typeMappings");
        if (tm instanceof Map<?,?> map) {
            for (Map.Entry<?,?> e : map.entrySet()) {
                Object k = e.getKey();
                Object v = e.getValue();
                if (k != null && v != null) {
                    result.put(String.valueOf(k).trim(), String.valueOf(v).trim());
                }
            }
        } else if (tm instanceof java.util.Collection<?> coll) {
            for (Object it : coll) {
                if (it instanceof Map<?,?> m) {
                    Object j = m.get("java");
                    Object t = m.get("ts");
                    if (j != null && t != null) {
                        result.put(String.valueOf(j).trim(), String.valueOf(t).trim());
                    }
                }
            }
        }

        // Default: Instant -> Date (falls nicht bereits vorhanden)
        result.putIfAbsent("Instant", "Date");
        result.putIfAbsent("java.time.Instant", "Date");

        return result;
    }

    /**
     * Liefert die konfigurierten Default-Imports, die an jeder generierten
     * TypeScript-Interface-Datei hinzugefügt werden sollen.
     *
     * YAML-Formate:
     *
     *  defaultImports:
     *    - "import { Foo } from 'types/Foo';"
     *    - "import { Bar } from '@scope/bar';"
     *
     *  oder als einzelner String:
     *  defaultImports: "import { Foo } from 'types/Foo';"
     */
    public java.util.List<String> getDefaultImports() {
        Object di = raw.get("defaultImports");
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (di == null) return new java.util.ArrayList<>();
        if (di instanceof String s) {
            String trimmed = s.trim();
            if (!trimmed.isBlank()) out.add(trimmed);
        } else if (di instanceof java.util.Collection<?> coll) {
            for (Object o : coll) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isBlank()) out.add(s);
            }
        } else if (di instanceof Map<?,?> map) {
            // selten, aber zur Robustheit: jede Value-Zeile übernehmen
            for (Object v : map.values()) {
                if (v == null) continue;
                String s = String.valueOf(v).trim();
                if (!s.isBlank()) out.add(s);
            }
        }
        return new java.util.ArrayList<>(out);
    }
}
