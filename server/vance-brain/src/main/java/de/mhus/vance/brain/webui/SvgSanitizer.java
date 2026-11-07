package de.mhus.vance.brain.webui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Makes an SVG safe to serve: the document is read by an XML parser (no DOCTYPE, so no external entities) and written
 * anew with only what an allow-list names. What is not in the list — {@code script}, {@code foreignObject},
 * {@code style}, {@code image}, {@code a}, the animation elements — is removed with its subtree; an attribute that
 * could run code or fetch something is removed from the element that stays. A document that cannot be read, or whose
 * root is no {@code svg}, is refused altogether: the asset is not served — {@code webui-assets.md} §5.
 *
 * <p>The SVG reaches the page as an image ({@code <img>}, {@code mask-image}), where it runs no code anyway; this is
 * the second layer, for whoever opens the URL directly.
 */
final class SvgSanitizer {

    private static final Logger log = LoggerFactory.getLogger(SvgSanitizer.class);

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    /** Elements that draw; everything else goes with its subtree. */
    private static final Set<String> ELEMENTS = Set.of(
            "svg",
            "g",
            "path",
            "circle",
            "ellipse",
            "rect",
            "line",
            "polyline",
            "polygon",
            "defs",
            "linearGradient",
            "radialGradient",
            "stop",
            "clipPath",
            "mask",
            "symbol",
            "use",
            "title",
            "desc",
            "text",
            "tspan");

    private SvgSanitizer() {}

    /**
     * @return the cleaned document, or empty when the input is no SVG the server will serve
     */
    static Optional<byte[]> sanitize(byte[] svg) {
        Document doc;
        try {
            doc = parse(svg);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.warn("SVG refused: not readable as XML ({})", e.getMessage());
            return Optional.empty();
        }
        Element root = doc.getDocumentElement();
        if (root == null || !"svg".equals(root.getLocalName()) || !SVG_NS.equals(root.getNamespaceURI())) {
            log.warn("SVG refused: the root is not an svg element");
            return Optional.empty();
        }
        clean(root);
        try {
            return Optional.of(write(doc));
        } catch (TransformerException e) {
            log.warn("SVG refused: could not be written ({})", e.getMessage());
            return Optional.empty();
        }
    }

    private static Document parse(byte[] svg) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(svg));
    }

    private static byte[] write(Document doc) throws TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    /** Removes what is not allowed below {@code element}, depth first. */
    private static void clean(Element element) {
        cleanAttributes(element);
        List<Node> remove = new ArrayList<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE -> {
                    Element el = (Element) child;
                    if (SVG_NS.equals(el.getNamespaceURI()) && ELEMENTS.contains(el.getLocalName())) {
                        clean(el);
                    } else {
                        remove.add(child);
                    }
                }
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {}
                default -> remove.add(child);
            }
        }
        remove.forEach(element::removeChild);
    }

    private static void cleanAttributes(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        List<Attr> remove = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            if (!allowed(attr)) remove.add(attr);
        }
        remove.forEach(element::removeAttributeNode);
    }

    private static boolean allowed(Attr attr) {
        String name = attr.getLocalName() != null ? attr.getLocalName() : attr.getName();
        String ns = attr.getNamespaceURI();
        String value = attr.getValue();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("on")) return false;
        if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(ns)) {
            // Declarations of the two namespaces the document may use; any other would bring in elements we drop
            // anyway.
            return SVG_NS.equals(value) || XLINK_NS.equals(value);
        }
        if (lower.equals("href")) return value.startsWith("#");
        if (lower.equals("style")) return safeStyle(value);
        // Everything else is geometry or presentation; a value that carries a URL is checked below.
        return !referencesOutside(value);
    }

    /** A {@code style} attribute is allowed when nothing in it fetches or runs. */
    private static boolean safeStyle(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("expression") || lower.contains("javascript:") || lower.contains("@import")) return false;
        return !referencesOutside(value);
    }

    /** Whether a value holds a {@code url(...)} that points anywhere but at an id in the same document. */
    private static boolean referencesOutside(String value) {
        // A CSS escape ({@code u\72l(}) spells "url(" past the search below; no icon needs one.
        if (value.indexOf('\\') >= 0) return true;
        String lower = value.toLowerCase(Locale.ROOT).replace(" ", "");
        int from = 0;
        while (true) {
            int at = lower.indexOf("url(", from);
            if (at < 0) return lower.contains("javascript:");
            int start = at + 4;
            while (start < lower.length() && (lower.charAt(start) == '\'' || lower.charAt(start) == '"')) start++;
            if (start >= lower.length() || lower.charAt(start) != '#') return true;
            from = start;
        }
    }
}
