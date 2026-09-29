package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Documents de la base de connaissances affichés dans la fenêtre (Word, Excel, PowerPoint…) et lus par RAF. */
class DocumentPreviewTest {

    @TempDir Path dir;

    private DocumentPreviewService svc() {
        DocumentPreviewService s = new DocumentPreviewService();
        ReflectionTestUtils.setField(s, "storageDir", dir.toString());
        return s;
    }

    @Test
    void wordIsRenderedWithHeadingsBoldAndTables() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(dir.resolve("gabs.docx"))) {
            XWPFRun title = doc.createParagraph().createRun();
            title.setText("Liste des GAB hors site");
            title.setBold(true);
            doc.createParagraph().createRun().setText("GAB de Cocody <Angré> : ouvert 24h/24");
            XWPFTable t = doc.createTable(2, 2);
            t.getRow(0).getCell(0).setText("Ville");
            t.getRow(0).getCell(1).setText("Adresse");
            t.getRow(1).getCell(0).setText("Abidjan");
            t.getRow(1).getCell(1).setText("Plateau");
            doc.write(out);
        }
        DocumentPreviewService.Preview p = svc().preview("/kb-files/gabs.docx", "LA LISTE DES GABS HORS SITE.docx");
        assertEquals("html", p.kind());
        assertTrue(p.html().contains("<b>Liste des GAB hors site</b>"));
        assertTrue(p.html().contains("&lt;Angré&gt;"), "contenu échappé, jamais de HTML brut");
        assertTrue(p.html().contains("<th><div>Ville</div></th>") && p.html().contains("<td>"));
        assertTrue(svc().text("/kb-files/gabs.docx").contains("Plateau"), "RAF lit le contenu du document");
    }

    @Test
    void excelShowsEverySheetAsATable() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(dir.resolve("tarifs.xlsx"))) {
            var s1 = wb.createSheet("Tarifs");
            s1.createRow(0).createCell(0).setCellValue("Produit");
            s1.getRow(0).createCell(1).setCellValue("Frais");
            s1.createRow(1).createCell(0).setCellValue("Carte Visa");
            s1.getRow(1).createCell(1).setCellValue(5000);
            wb.createSheet("Horaires").createRow(0).createCell(0).setCellValue("Lundi 8h-16h");
            wb.write(out);
        }
        String html = svc().preview("/kb-files/tarifs.xlsx", null).html();
        assertTrue(html.contains("data-dv-sheet=\"1\"") && html.contains("Horaires"), "un onglet par feuille");
        assertTrue(html.contains("Carte Visa") && html.contains("5"));
    }

    @Test
    void powerPointShowsSlides() throws Exception {
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(dir.resolve("brief.pptx"))) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("Nouveau plafond de retrait : 500 000 FCFA");
            show.write(out);
        }
        String html = svc().preview("/kb-files/brief.pptx", null).html();
        assertTrue(html.contains("dv-slide") && html.contains("500 000 FCFA"));
    }

    @Test
    void onlyKnowledgeBaseFilesCanBeOpened() {
        assertThrows(ApiException.class, () -> svc().preview("/etc/passwd", null));
        assertThrows(ApiException.class, () -> svc().preview("/kb-files/../../secret.txt", null));
        assertThrows(ApiException.class, () -> svc().preview("/kb-files/absent.docx", null));
    }

    @Test
    void csvAndTextArePreviewed() throws Exception {
        Files.writeString(dir.resolve("a.csv"), "Nom;Ville\nAwa;Lomé\n");
        assertTrue(svc().preview("/kb-files/a.csv", null).html().contains("<td>Lomé</td>"));
        Files.writeString(dir.resolve("b.txt"), "Note <importante>");
        assertTrue(svc().preview("/kb-files/b.txt", null).html().contains("&lt;importante&gt;"));
    }
}
