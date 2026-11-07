package ae.vattool.platform.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfRendererTest {

    private static final String LONG_BODY = "<p>row</p>".repeat(120);

    @Test
    void anEnglishPackStaysInTheBaseFontUntilItNeedsAnother() throws IOException {
        byte[] plain = PdfRenderer.render("Pack", "<p>Trading LLC 1,500.00</p>", "en", false);
        assertThat(fonts(plain)).allMatch(f -> f.contains("Helvetica"));

        byte[] withArabicName = PdfRenderer.render("Pack", "<p>Trading LLC شركة النور</p>", "en", false);
        assertThat(fonts(withArabicName)).anyMatch(f -> f.contains("Helvetica")).anyMatch(f -> f.contains("Amiri"));
        assertThat(arabicText(withArabicName)).contains("شركة النور");
    }

    /** A string in a margin box skips shaping, which printed this warning with its letters reversed. */
    @Test
    void theArabicWarningIsReadableInThePageHeaderOnEveryPage() throws IOException {
        byte[] pdf = PdfRenderer.render("Pack", LONG_BODY, "ar", true);
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(d.getNumberOfPages()).isGreaterThan(1);
            for (int page = 1; page <= d.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = Normalizer.normalize(stripper.getText(d), Normalizer.Form.NFKC);
                assertThat(text).as("page %d", page).contains(PdfRenderer.watermark("ar"));
            }
        }
    }

    @Test
    void theFooterCountsPagesInBothLanguages() throws IOException {
        for (String lang : List.of("en", "ar")) {
            byte[] pdf = PdfRenderer.render("Pack", LONG_BODY, lang, false);
            try (PDDocument d = Loader.loadPDF(pdf)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(1);
                String text = Normalizer.normalize(stripper.getText(d), Normalizer.Form.NFKC);
                assertThat(text).as(lang).contains(DocText.of(lang).t("doc.page")).contains(DocText.of(lang).t("doc.of"));
                assertThat(text).as(lang).contains(String.valueOf(d.getNumberOfPages()));
            }
        }
    }

    private static List<String> fonts(byte[] pdf) throws IOException {
        List<String> names = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            for (PDPage p : d.getPages()) {
                for (COSName n : p.getResources().getFontNames()) {
                    names.add(p.getResources().getFont(n).getName());
                }
            }
        }
        return names;
    }

    private static String arabicText(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return Normalizer.normalize(new PDFTextStripper().getText(d), Normalizer.Form.NFKC);
        }
    }
}
