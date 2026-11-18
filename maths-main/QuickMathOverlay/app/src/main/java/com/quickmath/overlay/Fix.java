package com.quickmath.overlay;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Called from CaptureService.processOnce while the screenshot bitmap is still alive. */
public final class Fix {
    private Fix() {}

    private static boolean mathy(String s) {
        if (s == null || s.length() > 60) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) && "xXOolI".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static boolean hasX(OcrLine l) { return l.getLeft() >= 0 && l.getRight() >= l.getLeft(); }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    public static List<OcrLine> buildLines(Text text, Bitmap bmp) {
        List<String> texts = new ArrayList<String>();
        List<Rect> rects = new ArrayList<Rect>();
        try {
            for (Text.TextBlock b : text.getTextBlocks()) {
                for (Text.Line l : b.getLines()) {
                    String s = l.getText();
                    if (s == null) continue;
                    texts.add(s);
                    rects.add(l.getBoundingBox());
                }
            }
        } catch (Throwable t) {
            return new ArrayList<OcrLine>();
        }
        List<OcrLine> plain = new ArrayList<OcrLine>();
        for (int i = 0; i < texts.size(); i++) {
            Rect r = rects.get(i);
            plain.add(r == null ? new OcrLine(texts.get(i), 0, 40, -1, -1) : new OcrLine(texts.get(i), r.top, r.bottom, r.left, r.right));
        }
        if (bmp == null) return plain;
        try {
            int refH = 0;
            for (int i = 0; i < texts.size(); i++) {
                Rect r = rects.get(i);
                if (r != null && mathy(texts.get(i)) && hasDigit(texts.get(i))) refH = Math.max(refH, r.height());
            }
            int W = bmp.getWidth(), H = bmp.getHeight();
            List<OcrLine> fixed = new ArrayList<OcrLine>();
            int analysed = 0;
            for (int i = 0; i < texts.size(); i++) {
                String s = texts.get(i);
                Rect r = rects.get(i);
                if (r == null) { fixed.add(plain.get(i)); continue; }
                String out = s;
                if (analysed < 50 && mathy(s)) {
                    analysed++;
                    String f = pixelFix(bmp, W, H, r, s, hasDigit(s) ? 0 : refH);
                    if (f != null && f.length() > 0) out = f;
                }
                fixed.add(new OcrLine(out, r.top, r.bottom, r.left, r.right));
            }
            return mergeRows(fixed);
        } catch (Throwable t) {
            return plain;
        }
    }

    private static String pixelFix(Bitmap bmp, int W, int H, Rect r, String text, int hdHint) {
        int lh = r.height();
        if (lh < 6 || lh > 400) return null;
        int rl = Math.max(0, r.left - (int) (2.4f * lh));
        int rr = Math.min(W, r.right + (int) (0.4f * lh));
        int rt = Math.max(0, r.top - (int) (0.12f * lh));
        int rb = Math.min(H, r.bottom + (int) (0.12f * lh));
        int rw = rr - rl, rh = rb - rt;
        if (rw < 4 || rh < 4 || rw * rh > 1500000) return null;
        int[] px = new int[rw * rh];
        bmp.getPixels(px, 0, rw, rl, rt, rw, rh);
        return OpVision.rebuild(px, rw, rh, r.left - rl, r.top - rt, r.right - rl, r.bottom - rt, text, hdHint);
    }

    private static final String OPS = "+-\u2212\u2013\u00d7*\u00f7/:xX=";

    private static boolean joinable(String a, String b) {
        String x = a.trim(), y = b.trim();
        if (x.length() == 0 || y.length() == 0) return false;
        if (!hasDigit(x) || !hasDigit(y)) return true;                // a bare operator piece
        return OPS.indexOf(x.charAt(x.length() - 1)) >= 0 || OPS.indexOf(y.charAt(0)) >= 0;
    }

    /** Pieces on the same row (e.g. a lone division sign and its number) become one line. */
    static List<OcrLine> mergeRows(List<OcrLine> in) {
        List<OcrLine> L = new ArrayList<OcrLine>(in);
        Collections.sort(L, new Comparator<OcrLine>() {
            @Override public int compare(OcrLine a, OcrLine b) { return Integer.compare(a.getLeft(), b.getLeft()); }
        });
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < L.size(); i++) {
                OcrLine a = L.get(i);
                if (!hasX(a)) continue;
                for (int j = 0; j < L.size(); j++) {
                    if (i == j) continue;
                    OcrLine b = L.get(j);
                    if (!hasX(b) || b.getLeft() < a.getLeft()) continue;
                    int ha = Math.max(1, a.getHeight()), hb = Math.max(1, b.getHeight());
                    int hm = Math.max(ha, hb);
                    double cya = (a.getTop() + a.getBottom()) / 2.0, cyb = (b.getTop() + b.getBottom()) / 2.0;
                    if (Math.abs(cya - cyb) > 0.35 * hm) continue;
                    int gap = b.getLeft() - a.getRight();
                    if (gap < -4 || gap > 1.6 * hm) continue;
                    // only join when one side is a bare operator or both are mathy
                    if (!mathy(a.getText()) || !mathy(b.getText())) continue;
                    if (!(joinable(a.getText(), b.getText()))) continue;
                    OcrLine m = new OcrLine(a.getText() + " " + b.getText(),
                            Math.min(a.getTop(), b.getTop()), Math.max(a.getBottom(), b.getBottom()),
                            Math.min(a.getLeft(), b.getLeft()), Math.max(a.getRight(), b.getRight()));
                    L.remove(Math.max(i, j));
                    L.remove(Math.min(i, j));
                    L.add(m);
                    Collections.sort(L, new Comparator<OcrLine>() {
                        @Override public int compare(OcrLine x, OcrLine y) { return Integer.compare(x.getLeft(), y.getLeft()); }
                    });
                    changed = true;
                    break outer;
                }
            }
        }
        return L;
    }
}
