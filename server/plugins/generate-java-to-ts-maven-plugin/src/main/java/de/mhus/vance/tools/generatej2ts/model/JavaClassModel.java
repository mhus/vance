package de.mhus.vance.tools.generatej2ts.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JavaClassModel {
    private String packageName;
    private String name;
    private JavaKind kind = JavaKind.CLASS;
    private String generateSubfolder; // from @GenerateTypeScript("subfolder")
    private String generateFileName;  // optional explicit output file name (e.g. "MyType.ts")
    private String generateInterfaceName; // optional explicit interface/enum name override
    private final List<JavaFieldModel> fields = new ArrayList<>(); // for CLASS only
    private final List<String> enumConstants = new ArrayList<>(); // for ENUM only
    private final Set<String> typeScriptImports = new HashSet<>(); // from @TypeScriptImport
    // Inner Enums declared inside a class
    private final List<JavaEnumModel> innerEnums = new ArrayList<>();

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public JavaKind getKind() { return kind; }
    public void setKind(JavaKind kind) { this.kind = kind; }

    public String getGenerateSubfolder() { return generateSubfolder; }
    public void setGenerateSubfolder(String generateSubfolder) { this.generateSubfolder = generateSubfolder; }

    public String getGenerateFileName() { return generateFileName; }
    public void setGenerateFileName(String generateFileName) { this.generateFileName = generateFileName; }

    public String getGenerateInterfaceName() { return generateInterfaceName; }
    public void setGenerateInterfaceName(String generateInterfaceName) { this.generateInterfaceName = generateInterfaceName; }

    public List<JavaFieldModel> getFields() { return fields; }
    public List<String> getEnumConstants() { return enumConstants; }
    public Set<String> getTypeScriptImports() { return typeScriptImports; }
    public List<JavaEnumModel> getInnerEnums() { return innerEnums; }
}
