package de.mhus.vance.tools.generatej2ts.ts;

public class TypeScriptField {
    private String name;
    private String tsType; // resolved TypeScript type
    private boolean optional;
    private String description; // optionaler Kommentar

    public TypeScriptField() {}

    public TypeScriptField(String name, String tsType, boolean optional) {
        this.name = name;
        this.tsType = tsType;
        this.optional = optional;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTsType() { return tsType; }
    public void setTsType(String tsType) { this.tsType = tsType; }

    public boolean isOptional() { return optional; }
    public void setOptional(boolean optional) { this.optional = optional; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
