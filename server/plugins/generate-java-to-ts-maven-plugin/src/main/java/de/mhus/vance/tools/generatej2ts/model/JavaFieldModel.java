package de.mhus.vance.tools.generatej2ts.model;

import java.util.HashSet;
import java.util.Set;

public class JavaFieldModel {
    private String name;
    private String javaType; // raw Java type from source
    private String tsTypeOverride; // from @TypeScript(type="...") optional
    private boolean optional;
    private boolean ignored;
    private boolean follow;
    private boolean staticFinal; // true, wenn Feld static final ist (Konstante)
    private String initializer; // Rohwert der Initialisierung (z. B. "\"PLAYER\"" oder 123)
    // Import-Metadaten aus @TypeScript
    // import_/tsImport/importValue → Symbolname
    // importPath → Pfad
    // importAs → Alias (optional)
    private String inlineImportLine; // komplette Import-Zeile (bevorzugt)
    private String importSymbol;
    private String importPath;
    private String importAs;
    // Altes Ein-Feld-Override (Backward-Compat, falls zuvor gesetzt)
    private String importOverride; // from @TypeScript(import="...")
    private String description; // from @TypeScript(description="...")

    private final Set<String> referencedTypes = new HashSet<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getJavaType() { return javaType; }
    public void setJavaType(String javaType) { this.javaType = javaType; }

    public String getTsTypeOverride() { return tsTypeOverride; }
    public void setTsTypeOverride(String tsTypeOverride) { this.tsTypeOverride = tsTypeOverride; }

    public boolean isOptional() { return optional; }
    public void setOptional(boolean optional) { this.optional = optional; }

    public boolean isIgnored() { return ignored; }
    public void setIgnored(boolean ignored) { this.ignored = ignored; }

    public boolean isFollow() { return follow; }
    public void setFollow(boolean follow) { this.follow = follow; }

    public boolean isStaticFinal() { return staticFinal; }
    public void setStaticFinal(boolean staticFinal) { this.staticFinal = staticFinal; }

    public String getInitializer() { return initializer; }
    public void setInitializer(String initializer) { this.initializer = initializer; }

    public String getImportSymbol() { return importSymbol; }
    public void setImportSymbol(String importSymbol) { this.importSymbol = importSymbol; }

    public String getImportPath() { return importPath; }
    public void setImportPath(String importPath) { this.importPath = importPath; }

    public String getImportAs() { return importAs; }
    public void setImportAs(String importAs) { this.importAs = importAs; }

    public String getInlineImportLine() { return inlineImportLine; }
    public void setInlineImportLine(String inlineImportLine) { this.inlineImportLine = inlineImportLine; }

    public String getImportOverride() { return importOverride; }
    public void setImportOverride(String importOverride) { this.importOverride = importOverride; }

    public Set<String> getReferencedTypes() { return referencedTypes; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
