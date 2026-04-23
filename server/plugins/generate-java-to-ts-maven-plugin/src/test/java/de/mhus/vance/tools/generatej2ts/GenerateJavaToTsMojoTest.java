package de.mhus.vance.tools.generatej2ts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Integrationstest: Startet das Mojo gegen Beispiel-Java-Dateien und prüft, dass TS-Dateien generiert werden.
 */
public class GenerateJavaToTsMojoTest {

    private Path outDir;

    @AfterEach
    void cleanup() throws IOException {
        if (outDir != null && Files.exists(outDir)) {
            // nicht rekursiv löschen, nur für wiederholte Läufe aufräumen
        }
    }

    @Test
    void generateTypeScriptFromSampleJava() throws Exception {
        File inputDir = locateResourceDir("java2ts/input");
        Assertions.assertTrue(inputDir.isDirectory(), "Input-Verzeichnis nicht gefunden: " + inputDir);

        outDir = Path.of("target", "test-output", "java2ts").toAbsolutePath();
        Files.createDirectories(outDir);

        GenerateJavaToTsMojo mojo = new GenerateJavaToTsMojo();
        setPrivateField(mojo, "inputDirectory", inputDir);
        setPrivateField(mojo, "outputDirectory", outDir.toFile());
        // Konfigurationsdatei mit defaultImports erstellen
        Path cfg = Path.of("target", "java-to-ts-test.yaml").toAbsolutePath();
        String yaml = "defaultImports:\n" +
                "  - \"import { Util } from 'utils/Util';\"\n" +
                "  - \"import something from '@scope/some';\"\n";
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, yaml, StandardCharsets.UTF_8);
        setPrivateField(mojo, "configFile", cfg.toFile());

        // execute
        mojo.execute();

        // verify files
        Path personTs = outDir.resolve(Path.of("models", "Person.ts"));
        Path addressTs = outDir.resolve(Path.of("models", "Address.ts"));
        Path humanTs = outDir.resolve(Path.of("models", "Human.ts"));
        Path statusTs = outDir.resolve(Path.of("enums", "Status.ts"));
        Path customTs = outDir.resolve(Path.of("models", "named", "Custom.ts"));
        Assertions.assertTrue(Files.exists(personTs), "Person.ts wurde nicht erzeugt");
        Assertions.assertTrue(Files.exists(addressTs), "Address.ts wurde nicht erzeugt");
        Assertions.assertTrue(Files.exists(humanTs), "Human.ts (Interface-Name aus Annotation) wurde nicht erzeugt");
        Assertions.assertTrue(Files.exists(statusTs), "Status.ts wurde nicht erzeugt");
        Assertions.assertTrue(Files.exists(customTs), "Custom.ts (benannter Output) wurde nicht erzeugt");

        // content checks
        String person = Files.readString(personTs, StandardCharsets.UTF_8);
        // Header mit Source-FQN prüfen
        Assertions.assertTrue(person.contains("Source: de.example.models.Person"), "Header enthält nicht die Java Source FQN");
        Assertions.assertTrue(person.contains("export interface Person"));
        Assertions.assertTrue(person.contains("name: string;"), "name: string fehlt");
        Assertions.assertTrue(person.contains("age?: number;"), "age?: number fehlt (optional)");
        Assertions.assertTrue(person.contains("age?: number; /* age in years */"), "Beschreibungskommentar fehlt oder falsch");
        // Klassen-Import aus @TypeScriptImport
        Assertions.assertTrue(person.contains("import { ColorHex } from '../types/ColorHex';"), "Import wurde nicht übernommen");
        // Default-Imports aus Konfiguration
        // Subfolder ist "models" -> Tiefe 1, daher muss '../' vorangestellt werden bei relativem Pfad
        Assertions.assertTrue(person.contains("import { Util } from '../utils/Util';"), "Default-Import wurde nicht relativ angepasst");
        // Alias '@...' darf nicht angepasst werden
        Assertions.assertTrue(person.contains("import something from '@scope/some';"), "Alias-Import '@' wurde fälschlich geändert");

        String status = Files.readString(statusTs, StandardCharsets.UTF_8);
        Assertions.assertTrue(status.contains("export enum Status"));
        Assertions.assertTrue(status.contains("ACTIVE"));
        Assertions.assertTrue(status.contains("INACTIVE"));

        // Prüfe das Default-Type-Mapping: Instant -> Date
        String address = Files.readString(addressTs, StandardCharsets.UTF_8);
        Assertions.assertTrue(address.contains("createdAt: Date;"), "Mapping Instant->Date wurde nicht angewendet");

        // Prüfe die benannte Datei: Dateiname aus Annotation, Interface-Name bleibt Class-Name
        String custom = Files.readString(customTs, StandardCharsets.UTF_8);
        Assertions.assertTrue(custom.contains("export interface CustomNamed"),
                "Interface-Name sollte 'CustomNamed' sein, trotz Dateiname Custom.ts");

        // Prüfe Interface-Umbenennung via @GenerateTypeScript(name="...")
        String human = Files.readString(humanTs, StandardCharsets.UTF_8);
        Assertions.assertTrue(human.contains("Source: de.example.models.Renamed"),
                "Header sollte die ursprüngliche Java-Klasse enthalten");
        Assertions.assertTrue(human.contains("export interface Human"),
                "Interface-Name sollte 'Human' sein");
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static File locateResourceDir(String rel) {
        URL url = Thread.currentThread().getContextClassLoader().getResource(rel);
        if (url == null) {
            throw new IllegalStateException("Resource not found: " + rel);
        }
        return new File(url.getFile());
    }
}
