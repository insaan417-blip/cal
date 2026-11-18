package com.quickmath.overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the operator symbols (+ - x /) of a line of text straight from its pixels.
 *
 * ML Kit regularly reports the division sign as "+" (or drops it), because the
 * Latin model barely knows it. The shapes are easy to tell apart though:
 *   division = three separate blobs (dot / bar / dot)
 *   plus     = one blob with a full vertical and a full horizontal stroke
 *   times    = one blob whose ink sits in the corners
 *   minus    = one very flat blob
 * Pure Java on an int[] of ARGB pixels so it can be unit-tested off-device.
 */
final class OpVision {
    private OpVision() {}

    private static final class G {
        int x0, y0, x1, y1;
        char kind;   // D digit, O operator, . dot, X other
        char op;     // + - * / = or ? when unknown
        int h() { return y1 - y0 + 1; }
        int w() { return x1 - x0 + 1; }
    }

    private static final String OCR_OPS =
            "+-\u2212\u2013\u2014\u2010\u2011\u2012\ufe63\uff0d\u00d7\u2715\u2716\u2a09\u2217\u00b7\u2219\u22c5*"
            + "\u00f7\u2797\u2215\u2044/:xX\uff0b=";

    /**
     * @param px     ARGB pixels of the region (rw x rh)
     * @param lL..lB the OCR line box, in region coordinates
     * @param ocr    what OCR said for this line
     * @param hdHint digit height measured on other lines (0 if unknown); used for operator-only lines
     * @return corrected text, or null if nothing reliable could be done
     */
    static String rebuild(int[] px, int rw, int rh, int lL, int lT, int lR, int lB, String ocr, int hdHint) {
        if (ocr == null || rw < 4 || rh < 4) return null;
        final int n = rw * rh;
        int lh = Math.max(1, lB - lT);

        // ---- background + ink mask
        int[] lum = new int[n];
        int[] hist = new int[32];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            int l = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
            lum[i] = l;
            hist[l >> 3]++;
        }
        int bin = 0;
        for (int b = 1; b < 32; b++) if (hist[b] > hist[bin]) bin = b;
        long sum = 0; int cnt = 0;
        for (int i = 0; i < n; i++) if ((lum[i] >> 3) == bin) { sum += lum[i]; cnt++; }
        int bg = cnt > 0 ? (int) (sum / cnt) : 0;
        int maxd = 0;
        for (int i = 0; i < n; i++) maxd = Math.max(maxd, Math.abs(lum[i] - bg));
        if (maxd < 60) return null;
        int thr = Math.max(45, maxd / 2);
        boolean[] ink = new boolean[n];
        for (int i = 0; i < n; i++) ink[i] = Math.abs(lum[i] - bg) >= thr;

        // ---- connected components (8-neighbourhood)
        int[] lab = new int[n];
        List<int[]> comps = new ArrayList<int[]>();   // x0,y0,x1,y1,area
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            if (!ink[s] || lab[s] != 0) continue;
            int id = comps.size() + 1;
            int sp = 0;
            stack[sp++] = s; lab[s] = id;
            int x0 = rw, y0 = rh, x1 = -1, y1 = -1, area = 0;
            while (sp > 0) {
                int p = stack[--sp];
                int px_ = p % rw, py_ = p / rw;
                area++;
                if (px_ < x0) x0 = px_; if (px_ > x1) x1 = px_;
                if (py_ < y0) y0 = py_; if (py_ > y1) y1 = py_;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = py_ + dy;
                    if (yy < 0 || yy >= rh) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = px_ + dx;
                        if (xx < 0 || xx >= rw) continue;
                        int q = yy * rw + xx;
                        if (ink[q] && lab[q] == 0) { lab[q] = id; stack[sp++] = q; }
                    }
                }
            }
            comps.add(new int[]{x0, y0, x1, y1, area});
        }

        // ---- keep components that belong to this text row
        List<int[]> keep = new ArrayList<int[]>();
        double yMin = lT - 0.12 * lh, yMax = lB + 0.12 * lh;
        for (int[] c : comps) {
            int h = c[3] - c[1] + 1, w = c[2] - c[0] + 1;
            double cy = (c[1] + c[3]) / 2.0;
            if (c[4] < 3) continue;
            if (cy < yMin || cy > yMax) continue;
            if (h > 1.5 * lh && hdHint == 0) continue;
            if (hdHint > 0 && h > 1.5 * hdHint) continue;
            if (w > 8 * Math.max(lh, hdHint)) continue;
            keep.add(c);
        }
        if (keep.isEmpty()) return null;
        java.util.Collections.sort(keep, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return Integer.compare(a[0], b[0]); }
        });

        // ---- merge blobs that overlap horizontally into glyphs (division sign = 3 blobs)
        List<G> glyphs = new ArrayList<G>();
        for (int[] c : keep) {
            G last = glyphs.isEmpty() ? null : glyphs.get(glyphs.size() - 1);
            if (last != null && c[0] <= last.x1) {
                last.x1 = Math.max(last.x1, c[2]);
                last.y0 = Math.min(last.y0, c[1]);
                last.y1 = Math.max(last.y1, c[3]);
            } else {
                G g = new G();
                g.x0 = c[0]; g.y0 = c[1]; g.x1 = c[2]; g.y1 = c[3];
                glyphs.add(g);
            }
        }

        // ---- digit height
        int hd = 0;
        for (G g : glyphs) hd = Math.max(hd, g.h());
        if (hdHint > 0 && hd < 0.8 * hdHint) hd = hdHint;
        boolean haveDigits = false;
        int digitTop = Integer.MAX_VALUE;
        for (G g : glyphs) if (g.h() >= 0.8 * hd) { haveDigits = true; digitTop = Math.min(digitTop, g.y0); }

        // ---- classify glyphs
        int digitGlyphs = 0, opGlyphs = 0;
        boolean anyDot = false, anyUnknown = false;
        for (G g : glyphs) {
            if (g.h() >= 0.8 * hd) { g.kind = 'D'; digitGlyphs++; continue; }
            double cy = (g.y0 + g.y1) / 2.0;
            boolean inBand = !haveDigits || (cy >= digitTop + 0.2 * hd && cy <= digitTop + 0.8 * hd);
            if (inBand && g.w() <= 1.2 * hd && (g.h() >= 0.07 * hd) && (g.h() >= 3 || g.w() >= 6)) {
                g.kind = 'O';
                g.op = classify(ink, rw, g, hd);
                if (g.op == '?') anyUnknown = true;
                opGlyphs++;
            } else if (haveDigits && cy > digitTop + 0.7 * hd && g.h() <= 0.3 * hd && g.w() <= 0.3 * hd) {
                g.kind = '.';
                anyDot = true;
            } else {
                return null;   // something we do not understand sits in this row
            }
        }

        // ---- OCR digits
        StringBuilder od = new StringBuilder();
        for (int i = 0; i < ocr.length(); i++) {
            char c = ocr.charAt(i);
            if (c >= '0' && c <= '9') od.append(c);
            else if (c == 'O' || c == 'o') od.append('0');
            else if (c == 'l' || c == 'I' || c == '|') od.append('1');
        }
        boolean ocrPunct = ocr.indexOf('.') >= 0 || ocr.indexOf(',') >= 0;

        // ---- full rebuild: structure from pixels, digit values from OCR
        if (!anyDot && !ocrPunct && !anyUnknown && digitGlyphs == od.length()) {
            StringBuilder sb = new StringBuilder();
            boolean prevDigit = false;
            G prev = null;
            for (G g : glyphs) {
                if (g.kind == 'D') {
                    if (prevDigit && prev != null && (g.x0 - prev.x1 - 1) <= 0.8 * hd) {
                        // same number
                    } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                        sb.append(' ');
                    }
                    sb.append(od.charAt(digitIndex(glyphs, g)));
                    prevDigit = true;
                } else {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                    sb.append(g.op).append(' ');
                    prevDigit = false;
                }
                prev = g;
            }
            return sb.toString().trim().replaceAll("\\s+", " ");
        }

        // ---- fallback: keep OCR text, only fix the operator characters
        if (opGlyphs == 0) return null;
        List<Integer> slots = new ArrayList<Integer>();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < ocr.length(); i++) {
            char c = ocr.charAt(i);
            if (OCR_OPS.indexOf(c) >= 0) { slots.add(t.length()); t.append(c); }
            else if (Character.isDigit(c) || Character.isWhitespace(c) || c == '.' || c == ',' || c == 'O' || c == 'o'
                    || c == 'l' || c == 'I' || c == '|') t.append(c);
            else return null;
        }
        List<G> ops = new ArrayList<G>();
        for (G g : glyphs) if (g.kind == 'O') ops.add(g);
        if (ops.size() == slots.size()) {
            for (int i = 0; i < ops.size(); i++) {
                char o = ops.get(i).op;
                if (o != '?') t.setCharAt(slots.get(i), o);
            }
            return t.toString();
        }
        if (ops.size() == slots.size() + 1 && ops.get(0).op != '?') {
            // OCR dropped the leading operator; it must sit left of the first digit
            G firstDigit = null;
            for (G g : glyphs) if (g.kind == 'D') { firstDigit = g; break; }
            if (firstDigit == null || ops.get(0).x1 < firstDigit.x0) {
                for (int i = 0; i < slots.size(); i++) {
                    char o = ops.get(i + 1).op;
                    if (o != '?') t.setCharAt(slots.get(i), o);
                }
                return ops.get(0).op + " " + t.toString().trim();
            }
        }
        return null;
    }

    private static int digitIndex(List<G> glyphs, G target) {
        int idx = 0;
        for (G g : glyphs) {
            if (g == target) return idx;
            if (g.kind == 'D') idx++;
        }
        return idx;
    }

    private static boolean any(boolean[] ink, int rw, int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                if (ink[y * rw + x]) return true;
        return false;
    }

    /** Returns one of + - * / = or '?'. ('/' means the division sign.) */
    static char classify(boolean[] ink, int rw, G g, int hd) {
        int gw = g.w(), gh = g.h();
        int cx = (g.x0 + g.x1) / 2;
        int runs = 0, longest = 0, cur = 0;
        boolean prev = false;
        for (int y = g.y0; y <= g.y1; y++) {
            boolean on = false;
            for (int x = Math.max(g.x0, cx - 1); x <= Math.min(g.x1, cx + 1); x++) if (ink[y * rw + x]) on = true;
            if (on) {
                if (!prev) { runs++; cur = 0; }
                cur++;
                longest = Math.max(longest, cur);
            }
            prev = on;
        }
        if (runs >= 3 && gh >= 0.3 * hd) return '/';
        if (runs == 2) return gw >= 0.4 * hd ? '=' : '?';
        if (runs == 0) return '?';
        if (gh <= 0.22 * hd || gw >= 2.2 * gh) return '-';
        double colFrac = (double) longest / gh;
        int my = (g.y0 + g.y1) / 2, on = 0;
        for (int x = g.x0; x <= g.x1; x++) {
            if (ink[my * rw + x] || ink[Math.max(g.y0, my - 1) * rw + x] || ink[Math.min(g.y1, my + 1) * rw + x]) on++;
        }
        double midFrac = (double) on / gw;
        if (colFrac >= 0.7 && midFrac >= 0.7) return '+';
        int cw = Math.max(2, (int) (gw * 0.3)), ch = Math.max(2, (int) (gh * 0.3));
        int corners = 0;
        if (any(ink, rw, g.x0, g.y0, g.x0 + cw - 1, g.y0 + ch - 1)) corners++;
        if (any(ink, rw, g.x1 - cw + 1, g.y0, g.x1, g.y0 + ch - 1)) corners++;
        if (any(ink, rw, g.x0, g.y1 - ch + 1, g.x0 + cw - 1, g.y1)) corners++;
        if (any(ink, rw, g.x1 - cw + 1, g.y1 - ch + 1, g.x1, g.y1)) corners++;
        if (corners >= 3 && colFrac < 0.7) return '*';
        return '?';
    }
}
