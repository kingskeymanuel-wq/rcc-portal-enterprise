package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base de connaissances — les documents joints s'ouvrent directement dans la fenêtre, sans téléchargement :
 * Word (titres, gras, listes, tableaux, images), Excel (un onglet par feuille), PowerPoint (diapositive par
 * diapositive), texte et CSV sont convertis en HTML côté serveur ; PDF et images sont affichés tels quels.
 * Le même moteur fournit le texte des documents à RAF, qui peut ainsi répondre à partir de leur contenu.
 * Tout le HTML produit est échappé : aucun script ni style venant du fichier n'est repris.
 */
@Service
public class DocumentPreviewService {

    public record Preview(String kind, String fileName, String html) {}

    private static final int MAX_ROWS = 1000, MAX_COLS = 40;
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024, MAX_TOTAL_IMAGES = 10L * 1024 * 1024;

    @Value("${rcc.uploads.kb-files-dir:}")
    private String storageDir;

    private final Map<String, Preview> previews = new ConcurrentHashMap<>();
    private final Map<String, String> texts = new ConcurrentHashMap<>();

    /** Chemin disque d'un fichier servi sous /kb-files/… — jamais en dehors du dossier de stockage. */
    Path resolve(String url) {
        if (url == null || storageDir == null || storageDir.isBlank()) throw ApiException.notFound("Document introuvable.");
        String name = url.trim();
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (!name.startsWith("/kb-files/")) throw ApiException.badRequest("Seuls les documents de la base de connaissances peuvent être affichés.");
        name = java.net.URLDecoder.decode(name.substring("/kb-files/".length()), StandardCharsets.UTF_8);
        Path dir = Path.of(storageDir).toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) throw ApiException.notFound("Document introuvable.");
        return file;
    }

    static String ext(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    public Preview preview(String url, String displayName) {
        Path file = resolve(url);
        String key = file.toString();
        Preview cached = previews.get(key);
        if (cached != null) return cached;
        String name = displayName == null || displayName.isBlank() ? file.getFileName().toString() : displayName;
        Preview p;
        try {
            p = switch (ext(file.getFileName().toString())) {
                case "pdf" -> new Preview("pdf", name, null);
                case "png", "jpg", "jpeg", "gif", "webp", "bmp" -> new Preview("image", name, null);
                case "docx" -> new Preview("html", name, docxHtml(file));
                case "xlsx", "xls" -> new Preview("html", name, sheetHtml(file));
                case "pptx" -> new Preview("html", name, pptxHtml(file));
                case "csv" -> new Preview("html", name, csvHtml(Files.readString(file, charsetOf(file))));
                case "txt" -> new Preview("html", name, "<pre class=\"dv-pre\">" + esc(Files.readString(file, charsetOf(file))) + "</pre>");
                case "doc", "ppt" -> new Preview("html", name, legacyHtml(file));
                default -> new Preview("none", name, null);
            };
        } catch (IOException | RuntimeException e) {
            p = new Preview("html", name, "<div class=\"dv-warn\">Ce document n'a pas pu être lu (fichier endommagé ou protégé par mot de passe).</div>");
        }
        if (previews.size() > 60) previews.clear();
        previews.put(key, p);
        return p;
    }

    /** Texte brut d'un document (pour RAF) — vide si illisible. */
    public String text(String url) {
        Path file;
        try {
            file = resolve(url);
        } catch (RuntimeException e) {
            return "";
        }
        return texts.computeIfAbsent(file.toString(), k -> {
            try {
                String t = switch (ext(file.getFileName().toString())) {
                    case "pdf" -> pdfText(file);
                    case "txt", "csv" -> Files.readString(file, charsetOf(file));
                    case "docx", "xlsx", "xls", "pptx", "doc", "ppt" -> htmlToText(preview(url, null).html());
                    default -> "";
                };
                return t.length() > 200_000 ? t.substring(0, 200_000) : t;
            } catch (IOException | RuntimeException e) {
                return "";
            }
        });
    }

    // ── Word ──────────────────────────────────────────────────────────────

    static String docxHtml(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder html = new StringBuilder("<div class=\"dv-doc\">");
            long[] imageBudget = {MAX_TOTAL_IMAGES};
            boolean inList = false;
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String inner = runsHtml(p, imageBudget);
                    boolean listItem = p.getNumID() != null;
                    if (listItem && !inList) { html.append("<ul>"); inList = true; }
                    if (!listItem && inList) { html.append("</ul>"); inList = false; }
                    if (inner.isBlank()) { if (!listItem) html.append("<div class=\"dv-gap\"></div>"); continue; }
                    String style = p.getStyle() == null ? "" : p.getStyle().toLowerCase(Locale.ROOT);
                    String tag = listItem ? "li" : style.contains("title") || style.contains("titre1") || style.equals("heading1") || style.contains("heading1") ? "h3"
                            : style.contains("heading2") || style.contains("titre2") ? "h4" : style.contains("heading") || style.contains("titre") ? "h5" : "p";
                    String align = p.getAlignment() == ParagraphAlignment.CENTER ? " style=\"text-align:center\"" : "";
                    html.append('<').append(tag).append(align).append('>').append(inner).append("</").append(tag).append('>');
                } else if (el instanceof XWPFTable t) {
                    if (inList) { html.append("</ul>"); inList = false; }
                    html.append("<div class=\"dv-table-wrap\"><table class=\"dv-table\">");
                    boolean first = true;
                    for (XWPFTableRow row : t.getRows()) {
                        html.append("<tr>");
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String tag = first ? "th" : "td";
                            html.append('<').append(tag).append('>');
                            for (XWPFParagraph cp : cell.getParagraphs()) {
                                String inner = runsHtml(cp, imageBudget);
                                if (!inner.isBlank()) html.append("<div>").append(inner).append("</div>");
                            }
                            html.append("</").append(tag).append('>');
                        }
                        html.append("</tr>");
                        first = false;
                    }
                    html.append("</table></div>");
                }
            }
            if (inList) html.append("</ul>");
            return html.append("</div>").toString();
        }
    }

    private static String runsHtml(XWPFParagraph p, long[] imageBudget) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : p.getRuns()) {
            String t = r.text();
            if (t != null && !t.isEmpty()) {
                String s = esc(t).replace("\n", "<br>").replace("\t", "&emsp;");
                if (r.isBold()) s = "<b>" + s + "</b>";
                if (r.isItalic()) s = "<i>" + s + "</i>";
                if (r.getUnderline() != null && r.getUnderline() != UnderlinePatterns.NONE) s = "<u>" + s + "</u>";
                String color = r.getColor();
                if (color != null && color.matches("[0-9A-Fa-f]{6}") && !color.equalsIgnoreCase("000000") && !color.equalsIgnoreCase("auto")) {
                    s = "<span style=\"color:#" + color + "\">" + s + "</span>";
                }
                sb.append(s);
            }
            for (XWPFPicture pic : r.getEmbeddedPictures()) {
                XWPFPictureData data = pic.getPictureData();
                if (data != null) sb.append(imgTag(data.getData(), data.suggestFileExtension(), imageBudget));
            }
        }
        return sb.toString();
    }

    // ── Excel ─────────────────────────────────────────────────────────────

    static String sheetHtml(Path file) throws IOException {
        try (Workbook wb = WorkbookFactory.create(file.toFile(), null, true)) {
            DataFormatter fmt = new DataFormatter(Locale.FRANCE);
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            StringBuilder tabs = new StringBuilder("<div class=\"dv-tabs\">");
            StringBuilder body = new StringBuilder();
            int shown = 0;
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) continue;
                Sheet sheet = wb.getSheetAt(s);
                int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS - 1);
                int lastCol = 0;
                for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (int c = Math.min(row.getLastCellNum(), MAX_COLS) - 1; c > lastCol; c--) {
                        if (!cellText(row.getCell(c), fmt, ev).isBlank()) { lastCol = c; break; }
                    }
                }
                tabs.append("<button type=\"button\" data-dv-sheet=\"").append(shown).append("\"").append(shown == 0 ? " class=\"on\"" : "")
                        .append('>').append(esc(sheet.getSheetName())).append("</button>");
                body.append("<div class=\"dv-sheet\" data-dv-sheet-pane=\"").append(shown).append("\"").append(shown == 0 ? "" : " hidden").append('>')
                        .append("<div class=\"dv-table-wrap\"><table class=\"dv-table dv-grid\">");
                int emptyRun = 0;
                for (int r = Math.max(0, sheet.getFirstRowNum()); r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    StringBuilder tr = new StringBuilder();
                    boolean any = false;
                    for (int c = 0; c <= lastCol; c++) {
                        Cell cell = row == null ? null : row.getCell(c);
                        String t = cellText(cell, fmt, ev);
                        if (!t.isBlank()) any = true;
                        boolean bold = cell != null && wb.getFontAt(cell.getCellStyle().getFontIndex()).getBold();
                        boolean num = cell != null && (cell.getCellType() == CellType.NUMERIC
                                || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC));
                        tr.append("<td").append(num ? " class=\"num\"" : "").append('>').append(bold ? "<b>" : "").append(esc(t)).append(bold ? "</b>" : "").append("</td>");
                    }
                    if (!any) { if (++emptyRun > 3) continue; } else emptyRun = 0;
                    body.append("<tr>").append(tr).append("</tr>");
                }
                if (sheet.getLastRowNum() >= MAX_ROWS) body.append("<tr><td colspan=\"").append(lastCol + 1).append("\" class=\"dv-more\">… ")
                        .append(sheet.getLastRowNum() + 1 - MAX_ROWS).append(" ligne(s) supplémentaire(s) non affichée(s)</td></tr>");
                body.append("</table></div></div>");
                shown++;
            }
            tabs.append("</div>");
            return "<div class=\"dv-xls\">" + (shown > 1 ? tabs : "") + body + "</div>";
        }
    }

    private static String cellText(Cell cell, DataFormatter fmt, FormulaEvaluator ev) {
        if (cell == null) return "";
        try {
            return fmt.formatCellValue(cell, ev);
        } catch (RuntimeException e) {
            return fmt.formatCellValue(cell);
        }
    }

    // ── PowerPoint ────────────────────────────────────────────────────────

    static String pptxHtml(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); XMLSlideShow show = new XMLSlideShow(in)) {
            StringBuilder html = new StringBuilder("<div class=\"dv-slides\">");
            long[] budget = {MAX_TOTAL_IMAGES};
            int n = 0;
            for (XSLFSlide slide : show.getSlides()) {
                n++;
                html.append("<section class=\"dv-slide\"><span class=\"dv-slide-no\">").append(n).append("</span>");
                String title = slide.getTitle();
                if (title != null && !title.isBlank()) html.append("<h4>").append(esc(title)).append("</h4>");
                for (XSLFShape shape : slide.getShapes()) appendShape(shape, title, html, budget);
                html.append("</section>");
            }
            return html.append("</div>").toString();
        }
    }

    private static void appendShape(XSLFShape shape, String title, StringBuilder html, long[] budget) {
        if (shape instanceof XSLFGroupShape g) {
            for (XSLFShape s : g.getShapes()) appendShape(s, title, html, budget);
        } else if (shape instanceof XSLFTextShape ts) {
            String text = ts.getText();
            if (text == null || text.isBlank() || text.trim().equals(title == null ? "" : title.trim())) return;
            html.append("<ul>");
            for (XSLFTextParagraph p : ts.getTextParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) html.append("<li>").append(esc(t.trim())).append("</li>");
            }
            html.append("</ul>");
        } else if (shape instanceof XSLFTable table) {
            html.append("<div class=\"dv-table-wrap\"><table class=\"dv-table\">");
            for (XSLFTableRow row : table.getRows()) {
                html.append("<tr>");
                for (XSLFTableCell cell : row.getCells()) html.append("<td>").append(esc(cell.getText())).append("</td>");
                html.append("</tr>");
            }
            html.append("</table></div>");
        } else if (shape instanceof XSLFPictureShape pic && pic.getPictureData() != null) {
            html.append(imgTag(pic.getPictureData().getData(), pic.getPictureData().suggestFileExtension(), budget));
        }
    }

    // ── Autres formats ────────────────────────────────────────────────────

    static String csvHtml(String content) {
        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0) return "";
        char sep = lines[0].chars().filter(ch -> ch == ';').count() >= lines[0].chars().filter(ch -> ch == ',').count() ? ';' : ',';
        StringBuilder html = new StringBuilder("<div class=\"dv-table-wrap\"><table class=\"dv-table\">");
        for (int i = 0; i < Math.min(lines.length, MAX_ROWS); i++) {
            html.append("<tr>");
            for (String cell : lines[i].split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1)) {
                String c = cell.trim();
                if (c.length() >= 2 && c.startsWith("\"") && c.endsWith("\"")) c = c.substring(1, c.length() - 1).replace("\"\"", "\"");
                html.append(i == 0 ? "<th>" : "<td>").append(esc(c)).append(i == 0 ? "</th>" : "</td>");
            }
            html.append("</tr>");
        }
        return html.append("</table></div>").toString();
    }

    /** Ancien format Word / PowerPoint (.doc, .ppt) : le texte lisible est extrait et affiché. */
    static String legacyHtml(Path file) throws IOException {
        byte[] b = Files.readAllBytes(file);
        StringBuilder text = new StringBuilder(), run = new StringBuilder();
        // Texte stocké en UTF-16LE (Word 97-2003) : paires octet lisible + 0.
        for (int i = 0; i + 1 < b.length; i += 2) {
            char c = (char) ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8));
            if (Character.isLetterOrDigit(c) || " .,;:'’!?()-/%€«»\"&@+=\r\n\t".indexOf(c) >= 0) run.append(c);
            else { if (run.toString().trim().length() >= 12) text.append(run).append('\n'); run.setLength(0); }
        }
        if (run.toString().trim().length() >= 12) text.append(run);
        if (text.toString().trim().length() < 40) {
            run.setLength(0);
            for (byte x : b) {
                char c = (char) (x & 0xFF);
                if (c >= 32 && c < 127 || c == '\n' || c == '\r') run.append(c);
                else { if (run.toString().trim().length() >= 12) text.append(run).append('\n'); run.setLength(0); }
            }
        }
        StringBuilder html = new StringBuilder("<div class=\"dv-doc\"><div class=\"dv-note\">Ancien format Office : aperçu du texte du document.</div>");
        for (String line : text.toString().split("[\\r\\n]+")) {
            if (line.trim().length() >= 3) html.append("<p>").append(esc(line.trim())).append("</p>");
        }
        return html.append("</div>").toString();
    }

    static String pdfText(Path file) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.pdmodel.PDDocument.load(file.toFile())) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    private static java.nio.charset.Charset charsetOf(Path file) throws IOException {
        byte[] b = Files.readAllBytes(file);
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b));
            return StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException e) {
            return java.nio.charset.Charset.forName("windows-1252");
        }
    }

    private static String imgTag(byte[] data, String ext, long[] budget) {
        if (data == null || data.length > MAX_IMAGE_BYTES || data.length > budget[0]) return "";
        String e = ext == null ? "png" : ext.toLowerCase(Locale.ROOT);
        String mime = switch (e) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "png" -> "image/png";
            default -> null; // emf, wmf… non affichables par le navigateur
        };
        if (mime == null) return "";
        budget[0] -= data.length;
        return "<img class=\"dv-img\" alt=\"\" src=\"data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data) + "\">";
    }

    static String htmlToText(String html) {
        if (html == null) return "";
        return html.replaceAll("<img[^>]*>", " ").replaceAll("</(p|li|tr|h\\d|div|section)>", "\n").replaceAll("</t[dh]>", " | ")
                .replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&emsp;", " ").replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
