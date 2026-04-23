package de.mhus.vance.tools.generatej2ts.ts;

import java.util.ArrayList;
import java.util.List;

public class TypeScriptNestedEnum {
    private String name;
    private final List<String> values = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getValues() { return values; }
}
