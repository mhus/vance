package de.mhus.vance.brain.tools.report;

import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.odftoolkit.odfdom.doc.OdfTextDocument;
import org.odftoolkit.odfdom.doc.table.OdfTable;
import org.odftoolkit.odfdom.doc.table.OdfTableCell;
import org.odftoolkit.odfdom.doc.table.OdfTableRow;
import org.odftoolkit.odfdom.dom.element.office.OfficeTextElement;
import org.odftoolkit.odfdom.incubator.doc.text.OdfTextHeading;
import org.odftoolkit.odfdom.incubator.doc.text.OdfTextParagraph;
import org.springframework.stereotype.Component;

/**
 * Renders a markdown report into an ODT (LibreOffice native) via
 * the commonmark-java &rarr; AST &rarr; odfdom-java pipeline.
 *
 * <p>Coverage is the same lego set as {@link DocxReportRenderer}:
 * headings, paragraphs (with inline bold/italic/code), bullet
 * and ordered lists, GFM tables, fenced and indented code blocks,
 * block-quotes. Inline links are kept verbatim with the URL in
 * parentheses — odfdom's native hyperlink path is heavy DOM
 * manipulation we don't need for the report use-case.
 *
 * <p>Lists are flattened to bullet-prefixed paragraphs (with
 * indent per nesting level). Real ODT lists need list-style
 * declarations in the document styles which adds noise; the
 * bullet-paragraph form opens cleanly in LibreOffice / Word and
 * matches the doc kind {@code list} convention from
 * {@code doc-kind-items.md}.
 */
@Component
@Slf4j
public class OdtReportRenderer implements MarkdownReportRenderer {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create());

    private final Parser parser = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    @Override public String format()        { return "odt"; }
    @Override public String mimeType()      { return "application/vnd.oasis.opendocument.text"; }
    @Override public String fileExtension() { return "odt"; }

    @Override
    public byte[] render(MarkdownReportContext context) {
        Node ast = parser.parse(context.markdown() == null ? "" : context.markdown());
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            OdfTextDocument doc = OdfTextDocument.newTextDocument();
            OfficeTextElement root = doc.getContentRoot();
            stripDefaultParagraph(root);

            if (context.title() != null && !context.title().isBlank()) {
                OdfTextHeading h = new OdfTextHeading(
                        (org.odftoolkit.odfdom.pkg.OdfFileDom) root.getOwnerDocument(),
                        "Title", context.title());
                root.appendChild(h);
            }
            applyCoreProperties(doc, context);

            OdtVisitor visitor = new OdtVisitor(doc, root);
            ast.accept(visitor);

            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ToolException(
                    "ODT rendering failed: " + e.getMessage());
        }
    }

    /** {@code OdfTextDocument.newTextDocument()} seeds the content
     *  with a default empty paragraph; strip it so our content
     *  starts at the top of the document without a leading blank.
     *  Note: {@code root.getFirstChild()} returns a
     *  {@code org.w3c.dom.Node}, not a commonmark {@code Node} —
     *  fully-qualified to disambiguate. */
    private static void stripDefaultParagraph(OfficeTextElement root) {
        org.w3c.dom.Node first = root.getFirstChild();
        if (first != null) root.removeChild(first);
    }

    private static void applyCoreProperties(OdfTextDocument doc, MarkdownReportContext context) {
        try {
            var meta = new org.odftoolkit.odfdom.incubator.meta.OdfOfficeMeta(
                    doc.getMetaDom());
            if (context.title() != null && !context.title().isBlank()) {
                meta.setTitle(context.title());
            }
            if (context.author() != null && !context.author().isBlank()) {
                meta.setCreator(context.author());
            }
        } catch (Exception ignored) {
            // metadata is nice-to-have; never fail over it
        }
    }

    /**
     * AST → odfdom walker. We deliberately avoid odfdom's
     * "incubator" inline-formatting helpers (spans with named
     * styles need style-declarations we'd have to register) and
     * fold inline emphasis into the surrounding text. For a
     * print-quality report that's acceptable; the user can
     * re-style locally in LibreOffice.
     */
    private static final class OdtVisitor extends AbstractVisitor {
        private final OdfTextDocument doc;
        private final OfficeTextElement root;
        private final org.odftoolkit.odfdom.pkg.OdfFileDom contentDom;
        private OdfTextParagraph currentParagraph;
        private int listDepth = 0;

        OdtVisitor(OdfTextDocument doc, OfficeTextElement root) {
            this.doc = doc;
            this.root = root;
            this.contentDom = (org.odftoolkit.odfdom.pkg.OdfFileDom) root.getOwnerDocument();
        }

        @Override
        public void visit(Heading h) {
            int level = Math.max(1, Math.min(6, h.getLevel()));
            String text = plaintextOf(h);
            OdfTextHeading heading = new OdfTextHeading(
                    contentDom, "Heading_20_" + level, text);
            root.appendChild(heading);
            currentParagraph = null;
        }

        @Override
        public void visit(Paragraph p) {
            // Paragraphs nested inside a list item get bullet-
            // prefixed handling in visit(ListItem) — those don't
            // recurse here. Top-level paragraphs append fresh.
            OdfTextParagraph par = new OdfTextParagraph(contentDom);
            root.appendChild(par);
            currentParagraph = par;
            par.addContent(plaintextOf(p));
            currentParagraph = null;
        }

        @Override
        public void visit(Text t) {
            // Visited only when called outside a structured branch
            // (rare — we mostly synthesise text via plaintextOf).
            if (currentParagraph == null) {
                currentParagraph = new OdfTextParagraph(contentDom);
                root.appendChild(currentParagraph);
            }
            currentParagraph.addContent(t.getLiteral());
        }

        @Override
        public void visit(FencedCodeBlock cb) {
            String body = cb.getLiteral() == null ? "" : cb.getLiteral();
            OdfTextParagraph par = new OdfTextParagraph(contentDom);
            par.addStyledContent("Preformatted_20_Text", body);
            root.appendChild(par);
            currentParagraph = null;
        }

        @Override
        public void visit(IndentedCodeBlock cb) {
            String body = cb.getLiteral() == null ? "" : cb.getLiteral();
            OdfTextParagraph par = new OdfTextParagraph(contentDom);
            par.addStyledContent("Preformatted_20_Text", body);
            root.appendChild(par);
            currentParagraph = null;
        }

        @Override
        public void visit(BlockQuote bq) {
            // Render as a single styled paragraph carrying the
            // quote's plain text. Nested blocks inside a quote
            // collapse — for report output that's fine.
            String text = plaintextOf(bq).trim();
            OdfTextParagraph par = new OdfTextParagraph(contentDom);
            par.addStyledContent("Quotations", text);
            root.appendChild(par);
            currentParagraph = null;
        }

        @Override
        public void visit(BulletList list) {
            listDepth++;
            visitChildren(list);
            listDepth--;
        }

        @Override
        public void visit(OrderedList list) {
            listDepth++;
            visitChildren(list);
            listDepth--;
        }

        @Override
        public void visit(ListItem item) {
            // Indent by depth (4 NBSP per level — visible in any
            // reader without needing list-style declarations).
            String indent = "    ".repeat(Math.max(0, listDepth - 1));
            String text = plaintextOf(item).trim();
            OdfTextParagraph par = new OdfTextParagraph(contentDom);
            par.addContent(indent + "• " + text);
            root.appendChild(par);
            currentParagraph = null;
        }

        @Override
        public void visit(CustomBlock customBlock) {
            if (customBlock instanceof TableBlock tb) {
                renderTable(tb);
                return;
            }
            visitChildren(customBlock);
        }

        private void renderTable(TableBlock block) {
            int columnCount = countColumns(block);
            int rowCount = countRows(block);
            if (columnCount == 0 || rowCount == 0) return;

            OdfTable table = OdfTable.newTable(doc, rowCount, columnCount);
            // newTable inserts at the end of the content already.
            int rowIdx = 0;
            for (Node n = block.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof TableHead head) {
                    for (Node r = head.getFirstChild(); r != null; r = r.getNext()) {
                        if (r instanceof TableRow row) {
                            populateRow(table, rowIdx++, row);
                        }
                    }
                }
            }
            for (Node n = block.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof TableBody body) {
                    for (Node r = body.getFirstChild(); r != null; r = r.getNext()) {
                        if (r instanceof TableRow row) {
                            populateRow(table, rowIdx++, row);
                        }
                    }
                }
            }
            currentParagraph = null;
        }

        private static int countColumns(TableBlock block) {
            for (Node n = block.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof TableHead head) {
                    for (Node r = head.getFirstChild(); r != null; r = r.getNext()) {
                        if (r instanceof TableRow row) {
                            int c = 0;
                            for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                                if (cell instanceof TableCell) c++;
                            }
                            return c;
                        }
                    }
                }
            }
            return 0;
        }

        private static int countRows(TableBlock block) {
            int rows = 0;
            for (Node n = block.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof TableHead head) {
                    for (Node r = head.getFirstChild(); r != null; r = r.getNext()) {
                        if (r instanceof TableRow) rows++;
                    }
                } else if (n instanceof TableBody body) {
                    for (Node r = body.getFirstChild(); r != null; r = r.getNext()) {
                        if (r instanceof TableRow) rows++;
                    }
                }
            }
            return rows;
        }

        private static void populateRow(OdfTable table, int rowIdx, TableRow row) {
            OdfTableRow tableRow = table.getRowByIndex(rowIdx);
            int col = 0;
            for (Node n = row.getFirstChild(); n != null; n = n.getNext()) {
                if (!(n instanceof TableCell cell)) continue;
                OdfTableCell tableCell = tableRow.getCellByIndex(col);
                tableCell.setStringValue(plaintextOf(cell));
                col++;
            }
        }

        @Override
        public void visit(Link link) {
            // Render link text inline + (url) trailing — same trade
            // -off as the DOCX renderer.
            String text = plaintextOf(link);
            String dest = link.getDestination();
            String full = (dest != null && !dest.isBlank())
                    ? text + " (" + dest + ")" : text;
            if (currentParagraph == null) {
                currentParagraph = new OdfTextParagraph(contentDom);
                root.appendChild(currentParagraph);
            }
            currentParagraph.addContent(full);
        }

        @Override public void visit(Emphasis e)        { visitChildren(e); }
        @Override public void visit(StrongEmphasis s)  { visitChildren(s); }
        @Override public void visit(Code c) {
            if (currentParagraph != null) currentParagraph.addContent(c.getLiteral());
        }
        @Override public void visit(SoftLineBreak b) {
            if (currentParagraph != null) currentParagraph.addContent(" ");
        }

        /** Flatten inline content to plain text. We use this for
         *  headings/paragraphs/cells where inline formatting would
         *  require styled-span machinery we deliberately skip. */
        private static String plaintextOf(Node node) {
            StringBuilder sb = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override public void visit(Text t)          { sb.append(t.getLiteral()); }
                @Override public void visit(Code c)          { sb.append(c.getLiteral()); }
                @Override public void visit(SoftLineBreak b) { sb.append(' '); }
            });
            return sb.toString();
        }
    }
}
