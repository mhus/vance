package de.mhus.vance.tools.generatej2ts;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.List;

import de.mhus.vance.tools.generatej2ts.collector.JavaCollector;
import de.mhus.vance.tools.generatej2ts.model.JavaClassModel;
import de.mhus.vance.tools.generatej2ts.ts.TypeScriptModel;
import de.mhus.vance.tools.generatej2ts.TypeScriptGenerator;
import de.mhus.vance.tools.generatej2ts.ts.TypeScriptModelWriter;

@Mojo(name = "generate")
public class GenerateJavaToTsMojo extends AbstractMojo {

    /**
     * Eingabeverzeichnis mit den Java-Dateien.
     */
    @Parameter(property = "inputDirectory", defaultValue = "${project.basedir}/src/main/java")
    private File inputDirectory;

    /**
     * Ausgabeverzeichnis für die generierten TypeScript-Dateien.
     */
    @Parameter(property = "outputDirectory", defaultValue = "${project.basedir}/src/main/ts-generated")
    private File outputDirectory;

    /**
     * Optionale Konfigurationsdatei (YAML), die das Verhalten steuert.
     */
    @Parameter(property = "configFile", defaultValue = "${project.basedir}/java-to-ts.yaml")
    private File configFile;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("generate-java-to-ts-maven-plugin gestartet");
        getLog().info("Input:  " + (inputDirectory == null ? "<null>" : inputDirectory.getAbsolutePath()));
        getLog().info("Output: " + (outputDirectory == null ? "<null>" : outputDirectory.getAbsolutePath()));
        getLog().info("Config: " + (configFile == null ? "<null>" : configFile.getAbsolutePath()));

        // Verzeichnisse prüfen/anlegen
        if (inputDirectory == null || !inputDirectory.isDirectory()) {
            throw new MojoExecutionException("inputDirectory existiert nicht oder ist kein Verzeichnis: " + inputDirectory);
        }

        if (outputDirectory != null && !outputDirectory.exists()) {
            boolean ok = outputDirectory.mkdirs();
            if (!ok) {
                throw new MojoExecutionException("Konnte outputDirectory nicht anlegen: " + outputDirectory);
            }
        }

        // Konfiguration laden (optional)
        Configuration configuration = null;
        try {
            configuration = Configuration.loadIfExists(configFile, getLog());
        } catch (IOException e) {
            throw new MojoExecutionException("Fehler beim Laden der Konfiguration: " + configFile, e);
        }

        int ruleCount = configuration == null ? 0 : configuration.countRules();
        getLog().info("Konfiguration geladen: " + ruleCount + " Regeln (falls vorhanden)");

        // JavaCollector ausführen: sammelt & parst alle relevanten Java-Klassen
        try {
            JavaCollector collector = new JavaCollector(inputDirectory, getLog());
            List<JavaClassModel> classes = collector.collect();
            getLog().info("JavaCollector: gefundene Klassen: " + (classes == null ? 0 : classes.size()));

            // TypeScriptModel erzeugen
            TypeScriptGenerator generator = new TypeScriptGenerator(getLog(), configuration);
            TypeScriptModel tsModel = generator.generate(classes);
            getLog().info("TypeScriptModel: Typen erzeugt: " + (tsModel == null ? 0 : tsModel.getTypes().size()));

            // TypeScriptModel in Dateien schreiben
            if (tsModel != null) {
                TypeScriptModelWriter writer = new TypeScriptModelWriter(getLog());
                try {
                    writer.writeModel(tsModel, outputDirectory);
                } catch (IOException ioe) {
                    throw new MojoExecutionException("Fehler beim Schreiben der TypeScript-Dateien", ioe);
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("JavaCollector fehlgeschlagen", e);
        }
    }
}
