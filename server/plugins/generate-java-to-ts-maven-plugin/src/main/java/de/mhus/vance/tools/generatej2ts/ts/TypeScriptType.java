package de.mhus.vance.tools.generatej2ts.ts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TypeScriptType {
    private String name;
    private TypeScriptKind kind = TypeScriptKind.INTERFACE;
    private final List<TypeScriptField> fields = new ArrayList<>(); // for INTERFACE
    private final List<String> enumValues = new ArrayList<>(); // for ENUM
    private final Set<String> imports = new HashSet<>();
    private String subfolder; // from @GenerateTypeScript on Java side
    private String sourceFqn; // fully-qualified Java source class name (package + name)
    private String fileName;  // optional target filename (e.g. "Custom.ts")
    // Nested enums that should be emitted into the same file (followed inner enums)
    private final List<TypeScriptNestedEnum> nestedEnums = new ArrayList<>();
    // Top-Level Konstanten (aus static final Feldern)
    private final List<TypeScriptConstant> constants = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TypeScriptKind getKind() { return kind; }
    public void setKind(TypeScriptKind kind) { this.kind = kind; }

    public List<TypeScriptField> getFields() { return fields; }
    public List<String> getEnumValues() { return enumValues; }
    public Set<String> getImports() { return imports; }

    public String getSubfolder() { return subfolder; }
    public void setSubfolder(String subfolder) { this.subfolder = subfolder; }

    public String getSourceFqn() { return sourceFqn; }
    public void setSourceFqn(String sourceFqn) { this.sourceFqn = sourceFqn; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public List<TypeScriptNestedEnum> getNestedEnums() { return nestedEnums; }

    public List<TypeScriptConstant> getConstants() { return constants; }
}
