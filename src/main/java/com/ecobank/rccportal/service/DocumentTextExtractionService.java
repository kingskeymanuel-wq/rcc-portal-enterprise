package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extraction de texte à partir d'un document uploadé (PDF, Word, texte brut)
 * — brique commune réutilisée partout où on veut remplacer la saisie
 * manuelle par un import de fichier (Procédures, Knowledge Base...).
 *
 * Limite honnête : un PDF scanné (image sans couche de texte) ne contient
 * aucun texte extractible par cette méthode — il faudrait de la
 * reconnaissance optique (OCR), qui n'est pas construite ici. Dans ce cas,
 * le texte extrait sera vide et l'appelant doit le signaler clairement à
 * l'utilisateur plutôt que de prétendre avoir réussi.
 */
@Service
public class DocumentTextExtractionService {

    /** Résultat structuré : le texte brut, plus un titre et des étapes déjà pressentis (heuristique simple). */
    public record ExtractedDocument(String rawText, String suggestedTitle, List<String> suggestedSteps) {
    }

    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*(\\d{1,2}|[a-zA-Z])[.)\\-]\\s+(.+)$");
    private static final Pattern BULLET_LINE = Pattern.compile("^\\s*[•\\-*▪●]\\s+(.+)$");

    public ExtractedDocument extract(MultipartFile file) {
        List<ExtractedDocument> all = extractMultiple(file);
        return all.get(0);
    }

    /**
     * Comme extract(), mais détecte si le document contient plusieurs sections distinctes
     * (ex. un PDF qui regroupe plusieurs procédures) et retourne une fiche par section trouvée.
     *
     * Heuristique : une ligne considérée comme un titre de section doit être entièrement en
     * MAJUSCULES, ne pas se terminer par une ponctuation de phrase, et contenir au moins deux
     * mots (pour éviter qu'un simple sigle isolé déclenche une coupure). Si moins de deux titres
     * de ce type sont trouvés dans tout le document, il est traité comme une seule fiche (même
     * comportement qu'avant) — mieux vaut ne pas couper que couper à tort.
     *
     * Non testée contre un vrai document multi-sections (contrairement à l'import KPI, validé
     * contre un vrai fichier) — à vérifier en pratique, le résultat d'import le signale.
     */
    /**
     * Pour RAF — mêmes formats que extract() (PDF, DOCX, TXT, CSV) PLUS Excel (.xlsx), lu
     * cellule par cellule en tableau texte lisible. Retourne le texte brut, tronqué à une
     * taille raisonnable sur un très gros fichier (jamais silencieusement — la troncature
     * est indiquée dans le texte lui-même).
     */
    public String extractForAnalysis(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                return extractSpreadsheet(file);
            }
            // Tout le texte du document : extract() ne renvoie que la PREMIÈRE section (découpage par titres en
            // majuscules, utile pour l'import de procédures) — un guide entier était réduit à sa page de garde.
            return readRawText(file);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Impossible de lire le fichier : " + e.getMessage());
        }
    }

    private String extractSpreadsheet(MultipartFile file) throws IOException {
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream())) {
            StringBuilder text = new StringBuilder();
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter();
            int maxRowsPerSheet = 300; // évite de dépasser la fenêtre de contexte sur un très gros fichier

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                var sheet = workbook.getSheetAt(s);
                text.append("Feuille : ").append(sheet.getSheetName()).append("\n");
                int rowCount = 0;
                for (var row : sheet) {
                    if (rowCount >= maxRowsPerSheet) {
                        text.append("[... suite tronquée, plus de ").append(maxRowsPerSheet).append(" lignes ...]\n");
                        break;
                    }
                    StringBuilder line = new StringBuilder();
                    for (var cell : row) {
                        if (line.length() > 0) line.append(" | ");
                        line.append(formatter.formatCellValue(cell));
                    }
                    if (!line.toString().isBlank()) {
                        text.append(line).append("\n");
                        rowCount++;
                    }
                }
                text.append("\n");
            }
            return text.toString();
        }
    }

    /** Texte brut complet du fichier (PDF, Office ancien ou récent, texte), d'après son contenu réel. */
    private String readRawText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        String rawText;

        try {
            // Format réel lu dans les premiers octets, pas dans l'extension : un ancien .doc renommé en .docx (cas
            // fréquent) passait par le lecteur Word moderne et échouait (« OLE2 Format… use HSSF instead of XSSF »).
            byte[] head = new byte[8];
            int read;
            try (var in = file.getInputStream()) { read = in.readNBytes(head, 0, 8); }
            boolean pdf = read >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
            boolean office = read >= 4 && ((head[0] == 'P' && head[1] == 'K')                       // .docx, .xlsx, .pptx
                    || ((head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0)); // .doc, .xls, .ppt
            if (pdf) {
                rawText = extractPdf(file);
            } else if (office) {
                rawText = extractOffice(file);
            } else if (filename.endsWith(".txt") || filename.endsWith(".csv") || filename.endsWith(".md")) {
                rawText = new String(file.getBytes(), StandardCharsets.UTF_8);
            } else {
                throw ApiException.badRequest("Format non pris en charge pour l'extraction automatique. Formats acceptés : "
                        + "PDF, Word (.docx, .doc), PowerPoint (.pptx, .ppt), Excel (.xlsx, .xls), TXT.");
            }
        } catch (IOException e) {
            throw ApiException.badRequest("Impossible de lire le fichier : " + e.getMessage());
        }

        if (rawText == null || rawText.isBlank()) {
            throw ApiException.badRequest(
                    "Aucun texte n'a pu être extrait de ce fichier. S'il s'agit d'un PDF scanné (une image, pas du texte " +
                    "sélectionnable), l'extraction automatique ne peut pas le lire — utilisez un fichier avec du texte réel.");
        }
        return rawText;
    }

    public List<ExtractedDocument> extractMultiple(MultipartFile file) {
        String rawText = readRawText(file);

        List<String> sections = splitIntoSections(rawText);
        List<ExtractedDocument> results = new ArrayList<>();
        for (String section : sections) {
            results.add(new ExtractedDocument(section, guessTitle(section), guessSteps(section)));
        }
        return results;
    }

    private List<String> splitIntoSections(String text) {
        String[] lines = text.split("\\r?\\n");
        List<Integer> boundaries = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (looksLikeSectionTitle(lines[i].trim())) boundaries.add(i);
        }

        if (boundaries.size() < 2) {
            return List.of(text); // pas assez de repères fiables — on ne coupe pas plutôt que de couper à tort
        }

        List<String> sections = new ArrayList<>();
        for (int b = 0; b < boundaries.size(); b++) {
            int start = boundaries.get(b);
            int end = (b + 1 < boundaries.size()) ? boundaries.get(b + 1) : lines.length;
            sections.add(String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)));
        }
        return sections;
    }

    private boolean looksLikeSectionTitle(String line) {
        if (line.isBlank() || line.length() > 100) return false;
        if (!line.equals(line.toUpperCase(java.util.Locale.FRENCH))) return false;
        if (line.endsWith(".") || line.endsWith(",") || line.endsWith(":")) return false;
        long letters = line.chars().filter(Character::isLetter).count();
        if (letters < 3) return false;
        long words = java.util.Arrays.stream(line.split("\\s+")).filter(w -> !w.isBlank()).count();
        return words >= 2;
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /** Word, PowerPoint et Excel, anciens et nouveaux formats : POI choisit le bon lecteur d'après le contenu. */
    private String extractOffice(MultipartFile file) throws IOException {
        if (isRightsProtected(file)) {
            throw ApiException.badRequest("Ce document est protégé par la gestion des droits Microsoft (étiquette de confidentialité "
                    + "Ecobank) : son contenu est chiffré et ne peut être lu par aucun autre logiciel. Ouvrez-le dans Word, puis "
                    + "enregistrez-le en PDF (Fichier → Exporter → PDF) ou retirez la protection, et déposez ce nouveau fichier.");
        }
        try (var in = file.getInputStream();
             var extractor = org.apache.poi.extractor.ExtractorFactory.createExtractor(in)) {
            return extractor.getText();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("document Office illisible (" + e.getMessage() + ")", e);
        }
    }

    /** Document Office chiffré (IRM / étiquette de confidentialité, ou mot de passe à l'ouverture) : conteneur OLE2
     *  qui ne contient que le paquet chiffré (EncryptedPackage), jamais le texte. */
    static boolean isRightsProtected(MultipartFile file) {
        try (var in = file.getInputStream(); var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(in)) {
            var root = fs.getRoot();
            return root.hasEntry("EncryptedPackage") || root.hasEntry("\u0006DataSpaces")
                    || root.getEntryNames().stream().anyMatch(n -> n.toUpperCase(java.util.Locale.ROOT).contains("DATASPACES"));
        } catch (Exception notOle2) {
            return false; // .docx/.xlsx/.pptx classique (ZIP) : pas de chiffrement de ce type
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /** Première ligne non vide du document — généralement le titre sur une procédure ou un article bien formé. */
    private String guessTitle(String text) {
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank() && trimmed.length() < 200) return trimmed;
        }
        return "";
    }

    /**
     * Repère les lignes numérotées ("1. ...", "2) ...") ou à puces ("- ...", "• ...") comme des étapes.
     * Si aucune n'est trouvée, retombe sur un découpage par paragraphe (lignes non vides), toujours
     * mieux que de forcer l'utilisateur à tout retaper à la main.
     */
    private List<String> guessSteps(String text) {
        List<String> numbered = new ArrayList<>();
        List<String> paragraphs = new ArrayList<>();

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            var numberedMatch = NUMBERED_LINE.matcher(trimmed);
            var bulletMatch = BULLET_LINE.matcher(trimmed);
            if (numberedMatch.matches()) {
                numbered.add(numberedMatch.group(2).trim());
            } else if (bulletMatch.matches()) {
                numbered.add(bulletMatch.group(1).trim());
            } else {
                paragraphs.add(trimmed);
            }
        }

        if (!numbered.isEmpty()) return numbered;
        // Pas de liste structurée détectée — le premier paragraphe est déjà pris comme titre,
        // le reste devient les étapes (une par paragraphe non vide).
        return paragraphs.size() > 1 ? paragraphs.subList(1, paragraphs.size()) : paragraphs;
    }
}
