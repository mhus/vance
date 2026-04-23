package de.mhus.vance.tools.generatej2ts.model;

import java.util.ArrayList;
import java.util.List;

public class JavaEnumModel {
    private String name;
    private final List<String> constants = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getConstants() { return constants; }
}
