package de.mhus.vance.brain.tools.report;

import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
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
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders a markdown report into a DOCX file via Apache POI XWPF.
 * Walks the commonmark AST and emits matching Word constructs —
 * paragraphs with run-level formatting, lists with indent levels,
 * tables built from the GFM-tables extension, code blocks as
 * monospaced paragraphs with a light shading.
 *
 * <p>Compared to the PDF path, this is a direct AST → POI visitor
 * because POI's DOM is verbose enough that going via HTML would
 * just add a (lossy) translation step.
 */
@Component
@Slf4j
public class DocxReportRenderer implements MarkdownReportRenderer {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create());

    private final Parser parser = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    @Override
    public String format() {
        return "docx";
    }

    @Override
    public String mimeType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override
    public String fileExtension() {
        return "docx";
    }

    @Override
    public byte[] render(MarkdownReportContext context) {
        Node ast = parser.parse(context.markdown() == null ? "" : context.markdown());
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            applyCoreProperties(doc, context);
            if (context.title() != null && !context.title().isBlank()) {
                XWPFParagraph titlePar = doc.createParagraph();
                titlePar.setStyle("Title");
                XWPFRun titleRun = titlePar.createRun();
                titleRun.setBold(true);
                titleRun.setFontSize(22);
                titleRun.setText(context.title());
            }
            DocxVisitor visitor = new DocxVisitor(doc);
            ast.accept(visitor);
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ToolException(
                    "DOCX rendering failed: " + e.getMessage());
        }
    }

    private static void applyCoreProperties(XWPFDocument doc, MarkdownReportContext context) {
        try {
            OPCPackage pkg = doc.getPackage();
            if (pkg == null || pkg.getPackageAccess() == PackageAccess.READ) return;
            var core = doc.getProperties().getCoreProperties();
            if (context.title() != null && !context.title().isBlank()) {
                core.setTitle(context.title());
            }
            if (context.author() != null && !context.author().isBlank()) {
                core.setCreator(context.author());
            }
        } catch (Exception ignored) {
            // Core-properties are nice-to-have; never fail the
            // export over them.
        }
    }

    /**
     * AST → XWPF walker. Maintains a current-paragraph + list-depth
     * so inline runs (text, code, emphasis) can target the right
     * parent. List indent uses POI's left-indent on the paragraph;
     * bullet character is plain Unicode (no numbering definitions,
     * which would need a styles.xml). Word renders the result as a
     * bulleted list, just not via Word's own list machinery — that's
     * a trade-off for simplicity that the user can fix locally with
     * one click after opening the file.
     */
    private static final class DocxVisitor extends AbstractVisitor {
        private static final BigInteger LIST_INDENT_TWIPS = BigInteger.valueOf(360);

        private final XWPFDocument doc;
        private @Nullable XWPFParagraph current;
        private @Nullable XWPFRun currentRun;
        private int listDepth = 0;
        private boolean inEmphasis = false;
        private boolean inStrong = false;
        private boolean inCode = false;

        DocxVisitor(XWPFDocument doc) { this.doc = doc; }

        @Override
        public void visit(Heading h) {
            current = doc.createParagraph();
            current.setStyle("Heading" + Math.min(h.getLevel(), 6));
            currentRun = current.createRun();
            currentRun.setBold(true);
            switch (h.getLevel()) {
                case 1 -> currentRun.setFontSize(18);
                case 2 -> currentRun.setFontSize(14);
                case 3 -> currentRun.setFontSize(12);
                default -> currentRun.setFontSize(11);
            }
            visitChildren(h);
            current = null;
            currentRun = null;
        }

        @Override
        public void visit(Paragraph p) {
            // Paragraphs nested inside list items reuse the list-item
            // paragraph instead of starting a new one — Word renders
            // them as the bullet's main line.
            boolean owns = current == null;
            if (owns) {
                current = doc.createParagraph();
                current.setAlignment(ParagraphAlignment.LEFT);
                currentRun = current.createRun();
            }
            visitChildren(p);
            if (owns) {
                current = null;
                currentRun = null;
            }
        }

        @Override
        public void visit(Text t) {
            if (current == null) {
                current = doc.createParagraph();
                currentRun = current.createRun();
            }
            XWPFRun run = current.createRun();
            applyInlineFormatting(run);
            run.setText(t.getLiteral());
        }

        @Override
        public void visit(SoftLineBreak b) {
            if (currentRun != null) {
                currentRun.addBreak();
            }
        }

        @Override
        public void visit(Emphasis e) {
            inEmphasis = true;
            visitChildren(e);
            inEmphasis = false;
        }

        @Override
        public void visit(StrongEmphasis s) {
            inStrong = true;
            visitChildren(s);
            inStrong = false;
        }

        @Override
        public void visit(Code c) {
            inCode = true;
            if (current == null) {
                current = doc.createParagraph();
            }
            XWPFRun run = current.createRun();
            applyInlineFormatting(run);
            run.setFontFamily("Courier New");
            run.setText(c.getLiteral());
            inCode = false;
        }

        @Override
        public void visit(Link link) {
            // No native hyperlink — POI requires manipulating the
            // package-level relationships for that, which is verbose
            // and brittle. We render the link text + URL inline so
            // nothing is lost; the user can re-link in Word/Pages.
            if (current == null) {
                current = doc.createParagraph();
            }
            visitChildren(link);
            if (link.getDestination() != null && !link.getDestination().isBlank()) {
                XWPFRun urlRun = current.createRun();
                urlRun.setColor("1A4A8A");
                urlRun.setText(" (" + link.getDestination() + ")");
            }
        }

        @Override
        public void visit(FencedCodeBlock cb) {
            XWPFParagraph par = doc.createParagraph();
            par.setStyle("Code");
            par.setSpacingBefore(120);
            par.setSpacingAfter(120);
            String body = cb.getLiteral() == null ? "" : cb.getLiteral();
            String info = cb.getInfo();
            if (info != null && !info.isBlank()) {
                XWPFRun infoRun = par.createRun();
                infoRun.setItalic(true);
                infoRun.setColor("666666");
                infoRun.setFontFamily("Helvetica");
                infoRun.setFontSize(9);
                infoRun.setText("[" + info.trim() + "]");
                infoRun.addBreak();
            }
            String[] lines = body.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                XWPFRun run = par.createRun();
                run.setFontFamily("Courier New");
                run.setFontSize(10);
                run.setText(lines[i]);
                if (i < lines.length - 1) run.addBreak();
            }
        }

        @Override
        public void visit(IndentedCodeBlock cb) {
            XWPFParagraph par = doc.createParagraph();
            par.setStyle("Code");
            String body = cb.getLiteral() == null ? "" : cb.getLiteral();
            String[] lines = body.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                XWPFRun run = par.createRun();
                run.setFontFamily("Courier New");
                run.setFontSize(10);
                run.setText(lines[i]);
                if (i < lines.length - 1) run.addBreak();
            }
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
            current = doc.createParagraph();
            current.setIndentationLeft(
                    LIST_INDENT_TWIPS.intValue() * Math.max(1, listDepth));
            currentRun = current.createRun();
            currentRun.setText("• ");
            visitChildren(item);
            current = null;
            currentRun = null;
        }

        @Override
        public void visit(BlockQuote bq) {
            current = doc.createParagraph();
            current.setIndentationLeft(720);
            current.setBorderLeft(org.apache.poi.xwpf.usermodel.Borders.SINGLE);
            currentRun = current.createRun();
            currentRun.setItalic(true);
            currentRun.setColor("444444");
            visitChildren(bq);
            current = null;
            currentRun = null;
        }

        @Override
        public void visit(ThematicBreak tb) {
            XWPFParagraph par = doc.createParagraph();
            par.setBorderBottom(org.apache.poi.xwpf.usermodel.Borders.SINGLE);
        }

        @Override
        public void visit(org.commonmark.node.CustomBlock customBlock) {
            if (customBlock instanceof TableBlock tb) {
                renderTable(tb);
                return;
            }
            visitChildren(customBlock);
        }

        private void renderTable(TableBlock block) {
            int columnCount = countColumns(block);
            if (columnCount == 0) return;

            XWPFTable table = doc.createTable(1, columnCount);
            table.setWidth("100%");

            int currentRow = 0;
            // Header row(s)
            for (Node n = block.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof TableHead head) {
                    for (Node r = head.getFirstChild(); r != null; r = r.getNext()) {
                        if (r instanceof TableRow row) {
                            XWPFTableRow tableRow = currentRow == 0
                                    ? table.getRow(0)
                                    : table.createRow();
                            populateRow(tableRow, row, true);
                            currentRow++;
                        }
                    }
                }
            }
            // Body row(s)
            for (Node n = block.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof TableBody body) {
                    for (Node r = body.getFirstChild(); r != null; r = r.getNext()) {
                        if (r instanceof TableRow row) {
                            XWPFTableRow tableRow = currentRow == 0
                                    ? table.getRow(0)
                                    : table.createRow();
                            populateRow(tableRow, row, false);
                            currentRow++;
                        }
                    }
                }
            }
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

        private void populateRow(XWPFTableRow tableRow, TableRow row, boolean header) {
            int col = 0;
            for (Node n = row.getFirstChild(); n != null; n = n.getNext()) {
                if (!(n instanceof TableCell cell)) continue;
                XWPFTableCell tableCell = col < tableRow.getTableCells().size()
                        ? tableRow.getCell(col)
                        : tableRow.addNewTableCell();
                // Wipe the placeholder paragraph that POI auto-creates
                if (!tableCell.getParagraphs().isEmpty()) {
                    tableCell.removeParagraph(0);
                }
                XWPFParagraph cellPar = tableCell.addParagraph();
                XWPFRun run = cellPar.createRun();
                if (header) {
                    run.setBold(true);
                }
                run.setText(plaintextOf(cell));
                col++;
            }
        }

        private static String plaintextOf(Node node) {
            StringBuilder sb = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override public void visit(Text t) { sb.append(t.getLiteral()); }
                @Override public void visit(Code c) { sb.append(c.getLiteral()); }
                @Override public void visit(SoftLineBreak b) { sb.append(' '); }
            });
            return sb.toString();
        }

        private void applyInlineFormatting(XWPFRun run) {
            if (inEmphasis) run.setItalic(true);
            if (inStrong) run.setBold(true);
            if (inCode) {
                run.setFontFamily("Courier New");
            }
        }
    }
}
