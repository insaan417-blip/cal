package com.quickmath.overlay;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds an arithmetic question in OCR output and solves it.
 *
 * Handles:
 *  - inline expressions ("12 + 5 - 3 = ?")
 *  - vertical stacks of any length ("23" / "+ 44" / "- 46" ...), up to 30 lines
 *  - + - x / with normal precedence, parentheses, unary minus, decimals
 */
public final class MathParserCore {
    public static final MathParserCore INSTANCE = new MathParserCore();
    public static final int $stable = 8;

    private static final Set<Character> ALL_OPS;
    static {
        Set<Character> s = new HashSet<Character>();
        s.add('+'); s.add('-'); s.add('*'); s.add('/');
        ALL_OPS = Collections.unmodifiableSet(s);
    }

    private static final int MAX_STACK = 30;
    private static final int MAX_TOKENS = 100;
    private static final int MAX_DIGITS = 18;
    private static final int MAX_DECIMALS = 6;

    private static final Pattern X_BETWEEN_DIGITS = Pattern.compile("(?<=[0-9])\\s*[xX]\\s*(?=[0-9.(\\-])");
    private static final Pattern WESTERN_GROUP = Pattern.compile("(?<![0-9.,])[0-9]{1,3}(?:,[0-9]{3})+(?![0-9,])");
    private static final Pattern INDIAN_GROUP = Pattern.compile("(?<![0-9.,])[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3}(?![0-9,])");
    private static final Pattern WORD = Pattern.compile("[0-9A-Za-z|]+");

    private MathParserCore() {}

    public final Set<Character> getALL_OPS() { return ALL_OPS; }

    // ------------------------------------------------------------------ tokens

    private static final int NUM = 0, OP = 1, LP = 2, RP = 3, JUNK = 4, EQ = 5;

    private static final class Tok {
        final int type;
        final String text;   // NUM text / JUNK char
        final char ch;       // OP char (+ - * /) or JUNK char
        boolean spBefore;    // whitespace directly before this token
        boolean spAfter;     // whitespace directly after this token
        boolean strong;      // unambiguous symbol (x, ÷, −): spacing around it does not matter
        boolean first;       // first token of the string
        boolean last;        // last token of the string

        Tok(int type, String text, char ch) { this.type = type; this.text = text; this.ch = ch; }
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == '\u00a0' || c == '\u2002' || c == '\u2003'
                || c == '\u2009' || c == '\u200a' || c == '\u202f';
    }

    private static char opOf(char c) {
        switch (c) {
            case '+': case '\uff0b': return '+';
            case '-': case '\u2212': case '\u2010': case '\u2011': case '\u2012': case '\u2013':
            case '\u2014': case '\ufe63': case '\uff0d': return '-';
            case '*': case '\u00d7': case '\u2715': case '\u2716': case '\u2a09': case '\u2217':
            case '\u00b7': case '\u2219': case '\u22c5': return '*';
            case '/': case '\u00f7': case '\u2797': case '\u2215': case '\u2044': return '/';
            default: return 0;
        }
    }

    /** Fix common OCR slips before tokenising. */
    private String clean(String raw) {
        String s = X_BETWEEN_DIGITS.matcher(raw).replaceAll(" * ");
        s = stripGroups(WESTERN_GROUP, s);
        s = stripGroups(INDIAN_GROUP, s);
        // letters that are really digits (O->0, l/I/|->1) but only inside digit words
        Matcher m = WORD.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String w = m.group();
            boolean hasDigit = false, ok = true;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (c >= '0' && c <= '9') hasDigit = true;
                else if (c != 'O' && c != 'o' && c != 'I' && c != 'l' && c != '|') { ok = false; break; }
            }
            if (hasDigit && ok) {
                w = w.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1');
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(w));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripGroups(Pattern p, String s) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replace(",", "")));
        m.appendTail(sb);
        return sb.toString();
    }

    private List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<Tok>();
        int n = s.length();
        int i = 0;
        boolean sawSpace = true;
        while (i < n) {
            char c = s.charAt(i);
            if (isSpace(c)) { sawSpace = true; i++; continue; }
            Tok t;
            if (c >= '0' && c <= '9' || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                while (j < n && Character.isDigit(s.charAt(j))) j++;
                if (j + 1 < n && s.charAt(j) == '.' && Character.isDigit(s.charAt(j + 1))) {
                    j++;
                    while (j < n && Character.isDigit(s.charAt(j))) j++;
                }
                t = new Tok(NUM, s.substring(i, j), '0');
                i = j;
            } else if (opOf(c) != 0) {
                t = new Tok(OP, String.valueOf(c), opOf(c));
                t.strong = !(c == '-' || c == '/' || c == '\u2044' || (c >= '\u2010' && c <= '\u2014')
                        || c == '\ufe63' || c == '\uff0d');
                i++;
            } else if (c == '(') { t = new Tok(LP, "(", '('); i++; }
            else if (c == ')') { t = new Tok(RP, ")", ')'); i++; }
            else if (c == '=' || c == '\uff1d') { t = new Tok(EQ, "=", '='); i++; }
            else if (c == ':' && sawSpace && i + 1 < n && isSpace(s.charAt(i + 1))) {
                t = new Tok(OP, ":", '/'); t.strong = true; i++;   // spaced colon used as a division sign
            } else {
                t = new Tok(JUNK, String.valueOf(c), c); i++;
            }
            t.spBefore = sawSpace;
            sawSpace = false;
            out.add(t);
            // whitespace after
            if (i < n && isSpace(s.charAt(i))) t.spAfter = true;
        }
        if (!out.isEmpty()) { out.get(0).first = true; out.get(out.size() - 1).last = true; }
        return out;
    }

    // ------------------------------------------------------------------ parser

    private static final class Fail extends RuntimeException {
        Fail() { super(null, null, false, false); }
    }
    private static final Fail FAIL = new Fail();

    private static final class Parser {
        final List<Tok> t;
        final Set<Character> allowed;
        int pos;
        int binary;

        Parser(List<Tok> t, Set<Character> allowed) { this.t = t; this.allowed = allowed; }

        Tok peek() { return pos < t.size() ? t.get(pos) : null; }

        BigDecimal parse() {
            BigDecimal v = expr(0);
            if (pos != t.size()) throw FAIL;
            return v;
        }

        BigDecimal expr(int depth) {
            BigDecimal v = term(depth);
            while (true) {
                Tok k = peek();
                if (k == null || k.type != OP || (k.ch != '+' && k.ch != '-')) break;
                if (!allowed.contains(k.ch)) throw FAIL;
                if (k.ch == '-' && !k.strong && !k.spBefore) throw FAIL;   // "925-478" is not read
                pos++; binary++;
                BigDecimal r = term(depth);
                v = k.ch == '+' ? v.add(r) : v.subtract(r);
            }
            return v;
        }

        BigDecimal term(int depth) {
            BigDecimal v = unary(depth);
            while (true) {
                Tok k = peek();
                if (k == null || k.type != OP || (k.ch != '*' && k.ch != '/')) break;
                if (!allowed.contains(k.ch)) throw FAIL;
                if (k.ch == '/' && !k.strong && !(k.spBefore && k.spAfter)) throw FAIL;   // "31/10" is not read
                pos++; binary++;
                BigDecimal r = unary(depth);
                if (k.ch == '*') v = v.multiply(r);
                else {
                    if (r.signum() == 0) throw FAIL;
                    try { v = v.divide(r); } catch (ArithmeticException e) { v = v.divide(r, MathContext.DECIMAL128); }
                }
            }
            return v;
        }

        BigDecimal unary(int depth) {
            Tok k = peek();
            if (k == null) throw FAIL;
            if (k.type == OP && k.ch == '-') {
                if (!allowed.contains('-')) throw FAIL;
                pos++;
                return unary(depth).negate();
            }
            return primary(depth);
        }

        BigDecimal primary(int depth) {
            Tok k = peek();
            if (k == null) throw FAIL;
            if (k.type == NUM) {
                pos++;
                int digits = 0;
                for (int i = 0; i < k.text.length(); i++) if (Character.isDigit(k.text.charAt(i))) digits++;
                if (digits > MAX_DIGITS) throw FAIL;
                return new BigDecimal(k.text);
            }
            if (k.type != LP || depth >= 6) throw FAIL;
            pos++;
            BigDecimal v = expr(depth + 1);
            Tok r = peek();
            if (r == null || r.type != RP) throw FAIL;
            pos++;
            return v;
        }
    }

    // ------------------------------------------------------------------ run evaluation

    private static final class Eval {
        Detection det;
        boolean attempted;   // looked like a question but could not be read
    }

    private static boolean hardNeighbour(Tok k) {
        if (k == null || k.type != JUNK) return false;
        char c = k.ch;
        return Character.isLetterOrDigit(c) || "_,.:;#%@&\\".indexOf(c) >= 0;
    }

    private Eval evalRun(List<Tok> run, Tok before, Tok after, Set<Character> allowed) {
        Eval ev = new Eval();
        int nums = 0, ops = 0;
        for (Tok k : run) { if (k.type == NUM) nums++; else if (k.type == OP) ops++; }
        if (nums < 2 || ops < 1) return ev;
        // embedded in a word / percentage etc. - not a question
        if (before != null && !run.get(0).spBefore && hardNeighbour(before)) return ev;
        if (after != null && !run.get(run.size() - 1).spAfter && hardNeighbour(after)) return ev;
        if (run.size() > MAX_TOKENS) { ev.attempted = true; return ev; }
        if (dateOrPhoneLike(run)) return ev;

        List<Tok> work = trimEnds(run);
        ev.attempted = true;
        ev.det = bestInRun(work, allowed);
        return ev;
    }

    /** Longest valid expression inside the run; refuses to cut one out of a longer broken one. */
    private Detection bestInRun(List<Tok> toks, Set<Character> allowed) {
        int n = toks.size();
        for (int len = n; len >= 3; len--) {
            for (int start = 0; start + len <= n; start++) {
                Tok before = start > 0 ? toks.get(start - 1) : null;
                Tok after = start + len < n ? toks.get(start + len) : null;
                if (!(before == null || before.type == RP)) continue;
                if (!(after == null || after.type == LP)) continue;
                List<Tok> seg = toks.subList(start, start + len);
                Tok first = seg.get(0), last = seg.get(seg.size() - 1);
                if (!(first.type == NUM || first.type == LP || (first.type == OP && first.ch == '-'))) continue;
                if (!(last.type == NUM || last.type == RP)) continue;
                Detection d = tryParse(seg, allowed);
                if (d != null) return d;
            }
        }
        return null;
    }

    /** Drop operators dangling at the ends ("+ 44 - 46 -"), keep a leading minus / paren. */
    private List<Tok> trimEnds(List<Tok> run) {
        int a = 0, b = run.size();
        while (a < b && run.get(a).type == OP && run.get(a).ch != '-') a++;
        while (b > a && run.get(b - 1).type == OP) b--;
        return run.subList(a, b);
    }

    private Detection tryParse(List<Tok> toks, Set<Character> allowed) {
        if (toks.size() < 3) return null;
        Parser p = new Parser(toks, allowed);
        try {
            BigDecimal v = p.parse();
            if (p.binary < 1) return null;
            return new Detection(render(toks), format(v));
        } catch (Fail f) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean dateOrPhoneLike(List<Tok> run) {
        int tight = 0;
        int maxDigits = 0;
        for (int i = 0; i < run.size(); i++) {
            Tok k = run.get(i);
            if (k.type == NUM) maxDigits = Math.max(maxDigits, k.text.length());
            if (k.type == OP && k.ch == '-' && !k.spBefore && !k.spAfter) tight++;
        }
        if (tight >= 2) return true;                       // 2024-01-05, 555-12-34
        if (tight == 1) {
            // 555-1234 style
            for (int i = 1; i + 1 < run.size(); i++) {
                Tok k = run.get(i);
                if (k.type == OP && k.ch == '-' && !k.spBefore && !k.spAfter
                        && run.get(i - 1).type == NUM && run.get(i + 1).type == NUM
                        && run.get(i - 1).text.length() >= 3 && run.get(i + 1).text.length() >= 4) return true;
            }
        }
        return false;
    }

    private String render(List<Tok> toks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toks.size(); i++) {
            Tok k = toks.get(i);
            if (k.type == NUM) sb.append(k.text);
            else if (k.type == LP) sb.append('(');
            else if (k.type == RP) sb.append(')');
            else if (k.type == OP) {
                Tok prev = i > 0 ? toks.get(i - 1) : null;
                boolean unary = prev == null || prev.type == OP || prev.type == LP;
                char sym = symbol(k.ch);
                if (unary) sb.append(sym);
                else sb.append(' ').append(sym).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static char symbol(char c) {
        if (c == '*') return '\u00d7';
        if (c == '/') return '\u00f7';
        if (c == '-') return '\u2212';
        return c;
    }

    // ------------------------------------------------------------------ text scanning

    private static final class TextOut {
        Detection det;
        int rejected;
        int nums;
    }

    private TextOut scanText(String text, Set<Character> allowed) {
        TextOut out = new TextOut();
        if (text == null || text.length() == 0) return out;
        List<Tok> toks = tokenize(clean(text));
        List<Tok> run = new ArrayList<Tok>();
        Tok before = null;
        for (int i = 0; i <= toks.size(); i++) {
            Tok k = i < toks.size() ? toks.get(i) : null;
            boolean breaker = k == null || k.type == JUNK || k.type == EQ;
            if (!breaker) { run.add(k); continue; }
            if (!run.isEmpty()) {
                Eval ev = evalRun(run, before, k, allowed);
                if (ev.det != null) {
                    out.det = ev.det;
                    for (Tok r : run) if (r.type == NUM) out.nums++;
                    return out;
                }
                if (ev.attempted) out.rejected++;
                run = new ArrayList<Tok>();
            }
            before = k;
            if (k != null && k.type == EQ) before = null;   // text after '=' is the answer part
        }
        return out;
    }

    // ------------------------------------------------------------------ stacks

    private static final class Cand {
        Detection det;
        int height;
        int nums;
    }

    private static boolean aligned(int mode, OcrLine a, OcrLine b, double tol) {
        if (!hasX(a) || !hasX(b)) return true;
        switch (mode) {
            case 0: return Math.abs(a.getRight() - b.getRight()) <= tol;
            case 1: return Math.abs(a.getLeft() - b.getLeft()) <= tol;
            default: return Math.abs((a.getLeft() + a.getRight()) / 2.0 - (b.getLeft() + b.getRight()) / 2.0) <= tol;
        }
    }

    private static boolean hasX(OcrLine l) { return l.getLeft() >= 0 && l.getRight() >= l.getLeft(); }

    private static boolean startsWithOp(String text) {
        String t = text.trim();
        if (t.length() == 0) return false;
        char c = t.charAt(0);
        if (opOf(c) != 0) return true;
        return (c == 'x' || c == 'X' || c == ':') && t.length() > 1;
    }

    private Cand scanStacks(List<OcrLine> lines, Set<Character> allowed, int[] rejected) {
        List<OcrLine> L = new ArrayList<OcrLine>();
        for (OcrLine l : lines) if (l.getText() != null && l.getText().trim().length() > 0 && l.getText().length() <= 60) L.add(l);
        Collections.sort(L, new Comparator<OcrLine>() {
            @Override public int compare(OcrLine a, OcrLine b) {
                return a.getTop() != b.getTop() ? Integer.compare(a.getTop(), b.getTop()) : Integer.compare(a.getLeft(), b.getLeft());
            }
        });
        if (L.size() > 80) L = new ArrayList<OcrLine>(L.subList(0, 80));
        int n = L.size();
        Cand best = null;
        boolean looksLikeStack = false;
        for (int mode = 0; mode < 3; mode++) {
            int[] next = new int[n];
            boolean[] hasPred = new boolean[n];
            for (int i = 0; i < n; i++) {
                next[i] = -1;
                OcrLine a = L.get(i);
                int ha = Math.max(1, a.getHeight());
                for (int j = i + 1; j < n; j++) {
                    OcrLine b = L.get(j);
                    int hb = Math.max(1, b.getHeight());
                    int hm = Math.max(ha, hb), hn = Math.min(ha, hb);
                    if (b.getTop() - a.getBottom() > 1.8 * hm) break;
                    int overlap = Math.min(a.getBottom(), b.getBottom()) - Math.max(a.getTop(), b.getTop());
                    if (overlap > 0.5 * hn) continue;                    // same row, not stacked
                    if ((double) hm / hn > 1.8) continue;
                    if (!aligned(mode, a, b, 0.75 * hm)) continue;
                    next[i] = j; hasPred[j] = true;
                    break;
                }
            }
            for (int s = 0; s < n; s++) {
                if (hasPred[s] || next[s] < 0) continue;
                List<OcrLine> chain = new ArrayList<OcrLine>();
                for (int c = s; c >= 0 && chain.size() < 60; c = next[c]) chain.add(L.get(c));
                if (chain.size() < 2) continue;
                Cand c = evalChain(chain, allowed);
                if (c != null) {
                    if (best == null || c.nums > best.nums || (c.nums == best.nums && c.height > best.height)) best = c;
                } else {
                    int opLines = 0;
                    for (OcrLine l : chain) if (startsWithOp(l.getText())) opLines++;
                    if (opLines >= 1) looksLikeStack = true;
                }
            }
        }
        if (best == null && looksLikeStack) rejected[0]++;
        return best;
    }

    private Cand evalChain(List<OcrLine> chain, Set<Character> allowed) {
        int n = Math.min(chain.size(), MAX_STACK);
        for (int len = n; len >= 2; len--) {
            for (int start = 0; start + len <= chain.size(); start++) {
                StringBuilder sb = new StringBuilder();
                int h = 0;
                for (int k = start; k < start + len; k++) {
                    if (k > start) sb.append(' ');
                    sb.append(chain.get(k).getText());
                    h = Math.max(h, chain.get(k).getHeight());
                }
                TextOut o = scanText(sb.toString(), allowed);
                if (o.det != null) {
                    Cand c = new Cand();
                    c.det = o.det; c.height = h; c.nums = o.nums;
                    return c;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ public entry

    public final ScanResult scan(List<OcrLine> lines, Set<Character> allowed) {
        if (allowed == null) allowed = ALL_OPS;
        int rejected = 0;
        Cand lineBest = null;
        try {
            for (OcrLine l : lines) {
                String t = l.getText();
                if (t == null || t.length() > 200) continue;
                TextOut o = scanText(t, allowed);
                rejected += o.rejected;
                if (o.det != null && (lineBest == null || l.getHeight() > lineBest.height)) {
                    lineBest = new Cand();
                    lineBest.det = o.det; lineBest.height = l.getHeight(); lineBest.nums = o.nums;
                }
            }
            int[] rej = new int[1];
            Cand stack = scanStacks(lines, allowed, rej);
            rejected += rej[0];
            Cand pick = lineBest;
            if (stack != null && (lineBest == null || stack.height * 1.15 >= lineBest.height)) pick = stack;
            return new ScanResult(pick == null ? null : pick.det, rejected);
        } catch (Throwable e) {
            return new ScanResult(null, rejected);
        }
    }

    public final String format(BigDecimal v) {
        BigDecimal r = v;
        if (r.scale() > MAX_DECIMALS) r = r.setScale(MAX_DECIMALS, RoundingMode.HALF_UP);
        if (r.signum() == 0 && v.signum() != 0) r = v.round(new MathContext(6));
        BigDecimal m = r.signum() == 0 ? BigDecimal.ZERO : r.stripTrailingZeros();
        return m.toPlainString();
    }
}
