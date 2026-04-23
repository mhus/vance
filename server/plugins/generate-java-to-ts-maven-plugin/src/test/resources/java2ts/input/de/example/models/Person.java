package de.example.models;

@GenerateTypeScript("models")
@TypeScriptImport("import { ColorHex } from '../types/ColorHex';")
public class Person {

    private String name;

    @TypeScript(optional=true, description="age in years")
    private Integer age;

    @TypeScript(follow=true)
    private Address address;

    @TypeScript(ignore=true)
    private String internalNote;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
