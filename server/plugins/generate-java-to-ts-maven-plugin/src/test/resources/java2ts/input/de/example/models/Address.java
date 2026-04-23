package de.example.models;

@GenerateTypeScript("models")
public class Address {

    private String street;
    private String city;

    @TypeScript(type="number")
    private Integer houseNumber;

    // Prüfen des Default-Mappings: Instant -> Date
    private java.time.Instant createdAt;
}
