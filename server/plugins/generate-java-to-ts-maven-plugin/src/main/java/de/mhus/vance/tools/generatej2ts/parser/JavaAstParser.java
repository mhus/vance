package de.mhus.vance.tools.generatej2ts.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.Type;
import de.mhus.vance.tools.generatej2ts.model.JavaClassModel;
import de.mhus.vance.tools.generatej2ts.model.JavaFieldModel;
import de.mhus.vance.tools.generatej2ts.model.JavaEnumModel;
import de.mhus.vance.tools.generatej2ts.model.JavaKind;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;

public class JavaAstParser {

    public static CompilationUnit parseCu(File file) throws IOException {
        // Ensure language level supports records and recent Java features
        ParserConfiguration cfg = StaticJavaParser.getConfiguration();
        LanguageLevel wanted = LanguageLevel.BLEEDING_EDGE; // tolerant to newest syntax
        if (cfg.getLanguageLevel() != wanted) {
            cfg.setLanguageLevel(wanted);
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return StaticJavaParser.parse(fis);
        }
    }

    public static boolean hasGenerateTypeScriptAnnotation(TypeDeclaration<?> type) {
        return type.getAnnotations().stream().anyMatch(a -> simpleName(a.getNameAsString()).equals("GenerateTypeScript"));
    }

    public static JavaClassModel toModel(CompilationUnit cu, TypeDeclaration<?> typeDecl) {
        JavaClassModel model = new JavaClassModel();
        model.setPackageName(cu.getPackageDeclaration().map(pd -> pd.getName().toString()).orElse(null));
        model.setName(typeDecl.getNameAsString());

        // @GenerateTypeScript("subfolder") or value may contain path and filename (ending with .ts)
        extractGenerateTypeScriptSubfolder(typeDecl).ifPresent(val -> {
            if (val != null) {
                String v = val.trim();
                if (v.endsWith(".ts")) {
                    int idx = v.lastIndexOf('/')
                            ;
                    if (idx >= 0) {
                        String folder = v.substring(0, idx);
                        String file = v.substring(idx + 1);
                        // normalize leading/trailing slashes
                        if (folder.equals(".")) folder = "";
                        if (folder.startsWith("/")) folder = folder.substring(1);
                        if (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
                        model.setGenerateSubfolder(folder);
                        model.setGenerateFileName(file);
                    } else {
                        // no slash, only file name given
                        model.setGenerateFileName(v);
                    }
                } else {
                    model.setGenerateSubfolder(v);
                }
            }
        });

        // Optional: Interface-/Enum-Name Override aus @GenerateTypeScript(name="...")
        extractGenerateTypeScriptName(typeDecl).ifPresent(model::setGenerateInterfaceName);

        if (typeDecl instanceof EnumDeclaration enumDecl) {
            model.setKind(JavaKind.ENUM);
            for (EnumConstantDeclaration c : enumDecl.getEntries()) {
                model.getEnumConstants().add(c.getNameAsString());
            }
            // class-level imports for TS (optional)
            extractTypeScriptImport(typeDecl).ifPresent(s -> {
                for (String line : s.split("\n")) {
                    model.getTypeScriptImports().add(line);
                }
            });
            return model;
        }

        if (typeDecl instanceof ClassOrInterfaceDeclaration clazz) {
            model.setKind(JavaKind.CLASS);

            // @TypeScriptImport on class
            extractTypeScriptImport(typeDecl).ifPresent(s -> {
                for (String line : s.split("\n")) {
                    model.getTypeScriptImports().add(line);
                }
            });

            for (BodyDeclaration<?> bd : clazz.getMembers()) {
                if (!(bd instanceof FieldDeclaration fd)) continue;
                // only fields considered
                NodeList<com.github.javaparser.ast.body.VariableDeclarator> vars = fd.getVariables();
                if (vars == null) continue;
                for (var v : vars) {
                    JavaFieldModel f = new JavaFieldModel();
                    f.setName(v.getNameAsString());
                    Type t = v.getType();
                    f.setJavaType(t.asString());
                    // Modifiers: static final → Konstante, nur public exportieren
                    boolean isStatic = fd.isStatic();
                    boolean isFinal = fd.isFinal();
                    f.setStaticFinal(isStatic && isFinal);
                    v.getInitializer().ifPresent(init -> f.setInitializer(init.toString()));
                    // analyze annotations on the field
                    for (AnnotationExpr an : fd.getAnnotations()) {
                        String n = simpleName(an.getNameAsString());
                        if (n.equals("TypeScript")) {
                            // read attributes: follow, type, import, ignore, optional
                            f.setFollow(getBooleanAttribute(an, "follow").orElse(false));
                            getStringAttribute(an, "type").ifPresent(f::setTsTypeOverride);
                            // Vollständige Importzeile bevorzugt direkt lesen
                            getStringAttribute(an, "importLine").ifPresent(f::setInlineImportLine);
                            // "import" ist als Attributname in Java nicht zulässig. Prüfe alternative Namen.
                            // Zerlege in strukturierte Felder, falls vorhanden (für Konstruktion der Importzeile)
                            getStringAttribute(an, "tsImport").ifPresent(f::setImportSymbol);
                            getStringAttribute(an, "import_").ifPresent(f::setImportSymbol);
                            getStringAttribute(an, "importValue").ifPresent(f::setImportSymbol);
                            getStringAttribute(an, "importPath").ifPresent(f::setImportPath);
                            getStringAttribute(an, "importAs").ifPresent(f::setImportAs);
                            f.setIgnored(getBooleanAttribute(an, "ignore").orElse(false));
                            f.setOptional(getBooleanAttribute(an, "optional").orElse(false));
                            getStringAttribute(an, "description").ifPresent(f::setDescription);
                        }
                    }
                    // try to collect referenced types from the raw java type (for follow)
                    TypeNameExtractor.extractReferencedSimpleTypes(f.getJavaType()).forEach(rt -> f.getReferencedTypes().add(rt));
                    model.getFields().add(f);
                }
            }

            // Collect inner enums for follow-support
            for (BodyDeclaration<?> bd : clazz.getMembers()) {
                if (bd instanceof EnumDeclaration innerEnum) {
                    JavaEnumModel em = new JavaEnumModel();
                    em.setName(innerEnum.getNameAsString());
                    for (EnumConstantDeclaration c : innerEnum.getEntries()) {
                        em.getConstants().add(c.getNameAsString());
                    }
                    model.getInnerEnums().add(em);
                }
            }
        }

        // Support for Java records (treated like classes with fields = record components)
        if (typeDecl instanceof RecordDeclaration recordDecl) {
            model.setKind(JavaKind.CLASS);

            // @TypeScriptImport on record
            extractTypeScriptImport(typeDecl).ifPresent(s -> {
                for (String line : s.split("\n")) {
                    model.getTypeScriptImports().add(line);
                }
            });

            // Components as fields
            NodeList<Parameter> params = recordDecl.getParameters();
            if (params != null) {
                for (Parameter p : params) {
                    JavaFieldModel f = new JavaFieldModel();
                    f.setName(p.getNameAsString());
                    Type t = p.getType();
                    f.setJavaType(t.asString());
                    // analyze annotations on the component (same as field)
                    for (AnnotationExpr an : p.getAnnotations()) {
                        String n = simpleName(an.getNameAsString());
                        if (n.equals("TypeScript")) {
                            f.setFollow(getBooleanAttribute(an, "follow").orElse(false));
                            getStringAttribute(an, "type").ifPresent(f::setTsTypeOverride);
                            getStringAttribute(an, "importLine").ifPresent(f::setInlineImportLine);
                            getStringAttribute(an, "tsImport").ifPresent(f::setImportSymbol);
                            getStringAttribute(an, "import_").ifPresent(f::setImportSymbol);
                            getStringAttribute(an, "importValue").ifPresent(f::setImportSymbol);
                            getStringAttribute(an, "importPath").ifPresent(f::setImportPath);
                            getStringAttribute(an, "importAs").ifPresent(f::setImportAs);
                            f.setIgnored(getBooleanAttribute(an, "ignore").orElse(false));
                            f.setOptional(getBooleanAttribute(an, "optional").orElse(false));
                            getStringAttribute(an, "description").ifPresent(f::setDescription);
                        }
                    }
                    TypeNameExtractor.extractReferencedSimpleTypes(f.getJavaType()).forEach(rt -> f.getReferencedTypes().add(rt));
                    model.getFields().add(f);
                }
            }

            // Collect inner enums in record body
            for (BodyDeclaration<?> bd : recordDecl.getMembers()) {
                if (bd instanceof EnumDeclaration innerEnum) {
                    de.mhus.vance.tools.generatej2ts.model.JavaEnumModel em = new de.mhus.vance.tools.generatej2ts.model.JavaEnumModel();
                    em.setName(innerEnum.getNameAsString());
                    for (EnumConstantDeclaration c : innerEnum.getEntries()) {
                        em.getConstants().add(c.getNameAsString());
                    }
                    model.getInnerEnums().add(em);
                }
            }

            return model;
        }

        return model;
    }

    private static Optional<String> extractGenerateTypeScriptSubfolder(TypeDeclaration<?> type) {
        for (AnnotationExpr an : type.getAnnotations()) {
            String n = simpleName(an.getNameAsString());
            if (n.equals("GenerateTypeScript")) {
                // Single value case
                if (an instanceof SingleMemberAnnotationExpr sm) {
                    return Optional.of(stripQuotes(sm.getMemberValue().toString()));
                }
                if (an instanceof NormalAnnotationExpr nn) {
                    for (MemberValuePair p : nn.getPairs()) {
                        if (p.getNameAsString().equals("value")) {
                            return Optional.of(stripQuotes(p.getValue().toString()));
                        }
                    }
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractGenerateTypeScriptName(TypeDeclaration<?> type) {
        for (AnnotationExpr an : type.getAnnotations()) {
            String n = simpleName(an.getNameAsString());
            if (n.equals("GenerateTypeScript") && an instanceof NormalAnnotationExpr nn) {
                for (MemberValuePair p : nn.getPairs()) {
                    if (p.getNameAsString().equals("name")) {
                        String s = stripQuotes(p.getValue().toString());
                        if (s != null && !s.isBlank()) return Optional.of(s);
                    }
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractTypeScriptImport(TypeDeclaration<?> type) {
        for (AnnotationExpr an : type.getAnnotations()) {
            String n = simpleName(an.getNameAsString());
            if (n.equals("TypeScriptImport")) {
                if (an instanceof SingleMemberAnnotationExpr sm) {
                    Expression val = sm.getMemberValue();
                    if (val instanceof ArrayInitializerExpr arr) {
                        StringBuilder sb = new StringBuilder();
                        for (Expression e : arr.getValues()) {
                            if (!sb.isEmpty()) sb.append('\n');
                            sb.append(stripQuotes(e.toString()));
                        }
                        return Optional.of(sb.toString());
                    } else {
                        return Optional.of(stripQuotes(val.toString()));
                    }
                }
                if (an instanceof NormalAnnotationExpr nn) {
                    for (MemberValuePair p : nn.getPairs()) {
                        if (p.getNameAsString().equals("value")) {
                            Expression val = p.getValue();
                            if (val instanceof ArrayInitializerExpr arr) {
                                StringBuilder sb = new StringBuilder();
                                for (Expression e : arr.getValues()) {
                                    if (!sb.isEmpty()) sb.append('\n');
                                    sb.append(stripQuotes(e.toString()));
                                }
                                return Optional.of(sb.toString());
                            } else {
                                return Optional.of(stripQuotes(val.toString()));
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Boolean> getBooleanAttribute(AnnotationExpr an, String name) {
        if (an instanceof NormalAnnotationExpr nn) {
            for (MemberValuePair p : nn.getPairs()) {
                if (p.getNameAsString().equals(name)) {
                    String s = p.getValue().toString();
                    if ("true".equalsIgnoreCase(s)) return Optional.of(true);
                    if ("false".equalsIgnoreCase(s)) return Optional.of(false);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> getStringAttribute(AnnotationExpr an, String name) {
        if (an instanceof NormalAnnotationExpr nn) {
            for (MemberValuePair p : nn.getPairs()) {
                if (p.getNameAsString().equals(name)) {
                    return Optional.of(stripQuotes(p.getValue().toString()));
                }
            }
        }
        return Optional.empty();
    }

    private static String simpleName(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
