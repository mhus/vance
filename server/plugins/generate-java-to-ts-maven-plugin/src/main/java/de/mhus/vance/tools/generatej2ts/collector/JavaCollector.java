package de.mhus.vance.tools.generatej2ts.collector;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import de.mhus.vance.tools.generatej2ts.model.JavaClassModel;
import de.mhus.vance.tools.generatej2ts.parser.JavaAstParser;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Sammelt alle Java-Dateien im inputDirectory, die eine Top-Level Klasse/Enum
 * mit @GenerateTypeScript enthalten, parst sie und erzeugt JavaClassModel-Objekte.
 *
 * Hinweis: Dies ist eine erste, minimale Implementierung. Sie folgt auf Wunsch
 * Typen mit Feldern, die @TypeScript(follow=true) gesetzt haben, anhand ihrer
 * Simple-Namen. Eine robuste Auflösung (Imports/Packages) kann später ergänzt werden.
 */
public class JavaCollector {

    private final File root;
    private final Log log;

    public JavaCollector(File root, Log log) {
        this.root = Objects.requireNonNull(root, "root");
        this.log = log;
    }

    public List<JavaClassModel> collect() throws IOException {
        Map<String, File> simpleNameToFile = new HashMap<>();
        List<File> javaFiles = new ArrayList<>();
        scanFiles(root, javaFiles);

        // Vorindexieren: Map SimpleName -> File (für follow)
        for (File f : javaFiles) {
            String name = stripJavaExtension(f.getName());
            simpleNameToFile.putIfAbsent(name, f);
        }

        List<JavaClassModel> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<File> queue = new ArrayDeque<>();
        // Dateien, die aufgrund von follow=true geladen wurden und ggf. ohne Annotation mitgenommen werden
        Set<String> forceInclude = new HashSet<>();

        // Start: nur Dateien, die @GenerateTypeScript enthalten
        for (File f : javaFiles) {
            CompilationUnit cu;
            try {
                cu = JavaAstParser.parseCu(f);
            } catch (Exception e) {
                if (log != null) log.warn("Überspringe Datei (Parsefehler): " + f + " -> " + e.getMessage());
                continue;
            }
            boolean has = false;
            for (TypeDeclaration<?> td : cu.getTypes()) {
                if (JavaAstParser.hasGenerateTypeScriptAnnotation(td)) {
                    has = true;
                    break;
                }
            }
            if (has) {
                queue.add(f);
            }
        }

        while (!queue.isEmpty()) {
            File f = queue.poll();
            if (f == null) break;
            if (!visited.add(f.getAbsolutePath())) continue; // schon verarbeitet

            CompilationUnit cu;
            try {
                cu = JavaAstParser.parseCu(f);
            } catch (Exception e) {
                if (log != null) log.warn("Überspringe Datei (Parsefehler): " + f + " -> " + e.getMessage());
                continue;
            }

            boolean isForcedFile = forceInclude.contains(f.getAbsolutePath());
            for (TypeDeclaration<?> td : cu.getTypes()) {
                boolean annotated = JavaAstParser.hasGenerateTypeScriptAnnotation(td);
                // Wenn per follow geladen: nimm zumindest Enums auch ohne Annotation mit
                boolean allowedByForce = false;
                if (isForcedFile) {
                    if (td.isEnumDeclaration()) {
                        allowedByForce = true;
                    }
                }
                if (!annotated && !allowedByForce) continue;

                JavaClassModel model = JavaAstParser.toModel(cu, td);
                if (log != null) log.info("JavaCollector: model parsed -> " + (model.getPackageName() == null ? model.getName() : (model.getPackageName() + "." + model.getName())));
                result.add(model);

                // follow: versuche referenzierte Typen von Feldern nachzuladen
                model.getFields().stream()
                        .filter(fld -> fld.isFollow() && !fld.isIgnored())
                        .forEach(fld -> {
                            for (String ref : fld.getReferencedTypes()) {
                                File refFile = simpleNameToFile.get(ref);
                                if (refFile != null && !visited.contains(refFile.getAbsolutePath())) {
                                    // Prüfen, ob der referenzierte Typ annotiert ist – wenn ja, normal hinzufügen;
                                    // wenn nein, bei follow trotzdem hinzufügen und als "forceInclude" markieren
                                    try {
                                        CompilationUnit refCu = JavaAstParser.parseCu(refFile);
                                        boolean has = false;
                                        for (TypeDeclaration<?> td2 : refCu.getTypes()) {
                                            if (JavaAstParser.hasGenerateTypeScriptAnnotation(td2)) {
                                                has = true; break;
                                            }
                                        }
                                        if (has) {
                                            queue.add(refFile);
                                        } else {
                                            queue.add(refFile);
                                            forceInclude.add(refFile.getAbsolutePath());
                                        }
                                    } catch (Exception e) {
                                        if (log != null) log.warn("Follow-Parsefehler bei " + refFile + ": " + e.getMessage());
                                    }
                                }
                            }
                        });
            }
        }

        if (log != null) log.info("JavaCollector fertig: " + result.size() + " Modelle");
        return result;
    }

    private void scanFiles(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                scanFiles(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private static String stripJavaExtension(String name) {
        if (name.endsWith(".java")) return name.substring(0, name.length() - 5);
        return name;
    }
}
