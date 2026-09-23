package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture « peu importe le fichier » pour l'import des résultats KPI : le format est reconnu
 * au CONTENU (signature des octets), pas seulement à l'extension, puis converti en classeur POI
 * pour passer par exactement le même moteur de reconnaissance (ManualKpiEntryService).
 *
 * Pris en charge : Excel .xlsx/.xlsm/.xls (y compris renommés), CSV/TSV/TXT (séparateur ; , tab |
 * détecté, encodage UTF-8 ou Windows-1252), tableaux HTML (exports « .xls » de nombreux outils
 * de reporting web), JSON (liste d'objets), PDF texte (rapports). Les images sont signalées
 * pour être lues par l'import « capture d'écran ».
 */
final class KpiFileReader {

    enum Kind { WORKBOOK, IMAGE }

    record Result(Kind kind, Workbook workbook) {}

    private KpiFileReader() {}

    static Result read(byte[] bytes, String filename, String contentType) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("Le fichier est vide.");

        if (type.startsWith("image/") || name.matches(".*\\.(png|jpe?g|gif|webp|bmp|tiff?)$") || isImage(bytes)) {
            return new Result(Kind.IMAGE, null);
        }
        if (name.endsWith(".xlsb")) {
            throw ApiException.badRequest("Le format Excel binaire (.xlsb) ne peut pas être lu : ouvrez-le dans Excel puis "
                    + "« Enregistrer sous » → « Classeur Excel (.xlsx) ». Aucune donnée n'est perdue.");
        }
        if (startsWith(bytes, "%PDF")) return new Result(Kind.WORKBOOK, fromPdf(bytes));

        boolean zip = bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
        boolean ole2 = bytes.length > 7 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0;
        if (zip && new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1).contains("opendocument")) {
            throw ApiException.badRequest("Fichier OpenDocument (.ods) : enregistrez-le au format .xlsx ou .csv depuis LibreOffice, puis réimportez-le.");
        }
        if (zip || ole2) {
            try {
                return new Result(Kind.WORKBOOK, WorkbookFactory.create(new ByteArrayInputStream(bytes)));
            } catch (org.apache.poi.EncryptedDocumentException e) {
                throw ApiException.badRequest("Ce classeur est protégé par un mot de passe : retirez la protection dans Excel puis réimportez-le.");
            }
        }

        String text = decode(bytes);
        String head = text.stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("<") && head.contains("<table")) return new Result(Kind.WORKBOOK, fromHtml(text));
        if (head.startsWith("[") || head.startsWith("{")) {
            Workbook json = fromJson(text);
            if (json != null) return new Result(Kind.WORKBOOK, json);
        }
        return new Result(Kind.WORKBOOK, fromDelimited(text));
    }

    // ───────────── Texte délimité (CSV / TSV / TXT) ─────────────

    /** UTF-8 (avec ou sans BOM) si valide, sinon Windows-1252 (exports Excel FR). */
    static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    static Workbook fromDelimited(String text) {
        List<String> lines = text.lines().toList();
        if (lines.stream().allMatch(String::isBlank)) throw ApiException.badRequest("Le fichier est vide.");
        char delimiter = detectDelimiter(lines);
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) rows.add(delimiter == ' ' ? splitSpaces(line) : splitDelimited(line, delimiter));
        return toWorkbook(rows);
    }

    /**
     * Séparateur : le premier candidat (tabulation, point-virgule, barre, virgule — dans cet
     * ordre de priorité) présent de façon RÉGULIÈRE (même nombre de séparateurs sur au moins
     * 70 % des lignes). La virgule passe en dernier : dans les exports français elle sert de
     * séparateur décimal (« 85,5;90,2 »). À défaut : colonnes séparées par des espaces multiples.
     */
    static char detectDelimiter(List<String> lines) {
        char[] candidates = {'\t', ';', '|', ','};
        List<String> sample = lines.stream().filter(l -> !l.isBlank()).limit(30).toList();
        if (sample.isEmpty()) return ';';
        for (char c : candidates) {
            Map<Integer, Integer> freq = new java.util.HashMap<>();
            for (String l : sample) {
                int n = splitDelimited(l, c).size() - 1;
                if (n > 0) freq.merge(n, 1, Integer::sum);
            }
            int modeCount = freq.values().stream().max(Integer::compare).orElse(0);
            int withSep = freq.values().stream().mapToInt(Integer::intValue).sum();
            // Régulier (même nombre de colonnes) ou, à défaut, présent sur presque toutes les lignes.
            if (modeCount >= Math.max(1, sample.size() * 0.6) || withSep >= sample.size() * 0.9) return c;
        }
        return ' ';
    }

    static List<String> splitDelimited(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else inQuotes = !inQuotes;
            } else if (ch == delimiter && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** Texte aligné (PDF, TXT) : colonnes séparées par au moins deux espaces ou une tabulation. */
    static List<String> splitSpaces(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.trim().split("\\t+|\\s{2,}")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    // ───────────── HTML / JSON / PDF ─────────────

    private static final Pattern TR = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern TD = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");

    static Workbook fromHtml(String html) {
        List<List<String>> rows = new ArrayList<>();
        Matcher tr = TR.matcher(html);
        while (tr.find()) {
            List<String> cells = new ArrayList<>();
            Matcher td = TD.matcher(tr.group(1));
            while (td.find()) {
                cells.add(td.group(1).replaceAll("(?is)<br\\s*/?>", " ").replaceAll("(?s)<[^>]+>", "")
                        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&#39;", "'").replace("&quot;", "\"").trim());
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (rows.isEmpty()) throw ApiException.badRequest("Aucun tableau lisible dans ce fichier HTML.");
        return toWorkbook(rows);
    }

    static Workbook fromJson(String text) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            com.fasterxml.jackson.databind.JsonNode array = root.isArray() ? root : null;
            if (array == null) {
                for (Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = root.fields(); it.hasNext(); ) {
                    com.fasterxml.jackson.databind.JsonNode v = it.next().getValue();
                    if (v.isArray()) { array = v; break; }
                }
            }
            if (array == null || array.isEmpty() || !array.get(0).isObject()) return null;
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            array.forEach(o -> o.fieldNames().forEachRemaining(keys::add));
            List<List<String>> rows = new ArrayList<>();
            rows.add(new ArrayList<>(keys));
            array.forEach(o -> {
                List<String> r = new ArrayList<>();
                for (String k : keys) r.add(o.has(k) && !o.get(k).isNull() ? o.get(k).asText() : "");
                rows.add(r);
            });
            return toWorkbook(rows);
        } catch (Exception e) {
            return null;
        }
    }

    static Workbook fromPdf(byte[] bytes) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                throw ApiException.badRequest("Ce PDF ne contient pas de texte (document scanné) : faites-en une capture d'écran "
                        + "et utilisez « Capture d'écran », ou exportez les données en Excel.");
            }
            List<List<String>> rows = new ArrayList<>();
            for (String line : text.lines().toList()) {
                List<String> cells = splitSpaces(line);
                if (cells.size() == 1) cells = splitNameThenNumbers(cells.get(0));
                if (!cells.isEmpty()) rows.add(cells);
            }
            return toWorkbook(rows);
        }
    }

    /** Ligne PDF à espaces simples : « KOUASSI Awa 85 92,5 12 » → [KOUASSI Awa, 85, 92,5, 12]. */
    static List<String> splitNameThenNumbers(String line) {
        String[] tokens = line.trim().split("\\s+");
        int firstNumber = tokens.length;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].matches("-?[\\d][\\d.,:%]*%?")) { firstNumber = i; break; }
        }
        List<String> out = new ArrayList<>();
        if (firstNumber == 0 || firstNumber == tokens.length) { out.add(line.trim()); return out; }
        out.add(String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, firstNumber)));
        for (int i = firstNumber; i < tokens.length; i++) out.add(tokens[i]);
        return out;
    }

    // ───────────── Utilitaires ─────────────

    static Workbook toWorkbook(List<List<String>> rows) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Import");
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            List<String> fields = rows.get(r);
            for (int c = 0; c < fields.size(); c++) {
                String value = fields.get(c) == null ? "" : fields.get(c).trim();
                Cell cell = row.createCell(c);
                if (value.matches("-?\\d+([.,]\\d+)?")) cell.setCellValue(Double.parseDouble(value.replace(',', '.')));
                else cell.setCellValue(value);
            }
        }
        return workbook;
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        if (bytes.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (bytes[i] != prefix.charAt(i)) return false;
        return true;
    }

    private static boolean isImage(byte[] b) {
        if (b.length < 4) return false;
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean jpg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
        boolean gif = b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        boolean webp = b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E';
        return png || jpg || gif || webp;
    }
}
