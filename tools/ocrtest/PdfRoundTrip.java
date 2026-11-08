import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import java.io.File;

/** Writes Thai words as an invisible, horizontally-stretched text layer (like the app) and reads it back. */
public class PdfRoundTrip {
    public static void main(String[] a) throws Exception {
        String[] words = {"ผู้ประกอบวิชาชีพกฎหมาย", "เกี่ยวข้อง", "ที่ไม่", "ทนายความ", "ยิ่งกว่า"};
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(); doc.addPage(page);
            PDFont font = PDType0Font.load(doc, new File(a[0]));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setRenderingMode(RenderingMode.NEITHER);
                float y = 700;
                for (String w : words) {
                    cs.setHorizontalScaling(130f);
                    cs.setTextMatrix(Matrix.getTranslateInstance(50, y));
                    cs.setFont(font, 14);
                    cs.showText(w);
                    y -= 30;
                }
                cs.endText();
            }
            for (boolean sort : new boolean[]{false, true}) {
                PDFTextStripper st = new PDFTextStripper();
                st.setSortByPosition(sort);
                String t = st.getText(doc);
                System.out.println("sortByPosition=" + sort + ": " + t.replace("\n", " | "));
                int tones = 0; for (char c : t.toCharArray()) if (c >= '่' && c <= '๋') tones++;
                int want = 0; for (String w : words) for (char c : w.toCharArray()) if (c >= '่' && c <= '๋') want++;
                System.out.println("  tones " + tones + " / " + want);
            }
        }
    }
}
