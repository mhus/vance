package de.mhus.vance.tools.generatej2ts.parser;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sehr einfache Extraktion von referenzierten Typnamen aus einem Java-Typ-String.
 * Ziel: Ermitteln der Simple-Namen, die potentiell weitere Klassen/Enums darstellen
 * (z. B. bei List<Foo> -> "Foo").
 */
public class TypeNameExtractor {

    private static final Set<String> JAVA_KEYWORDS = new LinkedHashSet<>(Arrays.asList(
            "byte","short","int","long","float","double","boolean","char","void",
            "var"
    ));

    private static final Set<String> WELL_KNOWN = new LinkedHashSet<>(Arrays.asList(
            "String","Integer","Long","Double","Float","Boolean","Character","Object",
            "List","Set","Collection","Map","Optional","ArrayList","HashSet","HashMap",
            "Record","BigDecimal","BigInteger"
    ));

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static Set<String> extractReferencedSimpleTypes(String typeString) {
        Set<String> result = new LinkedHashSet<>();
        if (typeString == null || typeString.isBlank()) return result;

        // Entferne Array-Klammern
        String s = typeString.replace("[]", "");
        // Ersetze Generics-Trenner durch Spaces
        s = s.replace('<',' ').replace('>',' ').replace(',', ' ');

        Matcher m = IDENT.matcher(s);
        while (m.find()) {
            String id = m.group();
            if (JAVA_KEYWORDS.contains(id)) continue;
            if (WELL_KNOWN.contains(id)) continue;
            // einfache Heuristik: Typen beginnen oft mit Großbuchstaben
            if (!id.isEmpty() && Character.isUpperCase(id.charAt(0))) {
                result.add(id);
            }
        }
        return result;
    }
}
