package de.mhus.vance.tools.generatej2ts.ts;

public class TypeScriptConstant {
    private String name;
    private String tsType; // optional Typannotation, falls kein Wert vorhanden
    private String value;  // Literalwert als String, z. B. "\"PLAYER\"", 123, true

    public TypeScriptConstant() {}

    public TypeScriptConstant(String name, String tsType, String value) {
        this.name = name;
        this.tsType = tsType;
        this.value = value;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTsType() { return tsType; }
    public void setTsType(String tsType) { this.tsType = tsType; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
