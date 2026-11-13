package com.puyo.overlay;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 화면 인식 — macro_hub 의 board_reader / digits / chance 판독부(웹 버전 reader.js 와 동일 로직).
 * 좌표는 1080x2340 기준을 해상도에 비례해 스케일하고, 격자 보정(Calib)을 더한다.
 * 칸 문자: R B G Y P 색, H 하트, X 프리즘, O 방해뿌요, . 빈칸
 */
public final class Reader {
    public static final int COLS = 8, ROWS = 6;
    static final int BASE_W = 1080, BASE_H = 2340;
    static final int CELL_W = 130, CELL_H = 122, X_FIRST = 84, Y_FIRST = 1496, Y_PREVIEW = 1406;
    static final double CHANCE_RATIO = 0.88;
    static final int CHANCE_MAX_CELLS = 8;
    static final int[] DIGIT_ROI = {322, 100, 404, 185};
    static final int TPL_W = 32, TPL_H = 48;
    static final double MIN_SCORE = 0.60;

    private Reader() {}

    public static final class Calib {
        public double dx = 0, dy = 0, kx = 1, ky = 1;
    }

    public static final class Geom {
        public final double[] xs = new double[COLS];
        public final double[] ys = new double[ROWS];
        public double yp, sx, sy, cw, ch;
    }

    public static final class Board {
        public char[][] grid;
        public char[] preview;
        public Geom geom;
    }

    public static final class Digit {
        public int n = -1;
        public double score;
    }

    /* ------------------------------------------------------------ 색 변환 (OpenCV 8bit HSV) */
    private static final class Patch {
        int w, h, n;
        int[] hh, ss, vv;
    }

    private static int[] clipRect(PixelSource img, double x0, double y0, double x1, double y1) {
        int ix0 = Math.max(0, (int) x0), iy0 = Math.max(0, (int) y0);
        int ix1 = Math.min(img.width(), (int) x1), iy1 = Math.min(img.height(), (int) y1);
        return new int[]{ix0, iy0, ix1, iy1};
    }

    private static Patch patchHSV(PixelSource img, double x0, double y0, double x1, double y1) {
        int[] r = clipRect(img, x0, y0, x1, y1);
        Patch p = new Patch();
        p.w = Math.max(0, r[2] - r[0]);
        p.h = Math.max(0, r[3] - r[1]);
        p.n = p.w * p.h;
        p.hh = new int[p.n];
        p.ss = new int[p.n];
        p.vv = new int[p.n];
        int k = 0;
        for (int y = r[1]; y < r[3]; y++) {
            for (int x = r[0]; x < r[2]; x++, k++) {
                int c = img.rgb(x, y);
                int rr = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int mx = Math.max(rr, Math.max(g, b)), mn = Math.min(rr, Math.min(g, b));
                int d = mx - mn;
                int h = 0, s = 0;
                if (mx > 0) s = (int) Math.round(255.0 * d / mx);
                if (d > 0) {
                    double hd;
                    if (mx == rr) hd = 60.0 * (g - b) / d;
                    else if (mx == g) hd = 120.0 + 60.0 * (b - rr) / d;
                    else hd = 240.0 + 60.0 * (rr - g) / d;
                    if (hd < 0) hd += 360;
                    h = (int) Math.round(hd / 2);
                    if (h >= 180) h -= 180;
                }
                p.hh[k] = h;
                p.ss[k] = s;
                p.vv[k] = mx;
            }
        }
        return p;
    }

    private static char hueClass(double h) {
        if (h < 8 || h >= 165) return 'R';
        if (h < 40) return 'Y';
        if (h < 85) return 'G';
        if (h < 125) return 'B';
        return 'P';
    }

    private static int classIdx(char c) {
        return c == 'R' ? 0 : c == 'Y' ? 1 : c == 'G' ? 2 : c == 'B' ? 3 : 4;
    }

    private static char classifyPatch(Patch P) {
        int n = P.n;
        if (n == 0) return '.';
        int cnt = 0;
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            if (P.ss[i] > 110 && P.vv[i] > 90) {
                cnt++;
                double a = P.hh[i] * 2 * Math.PI / 180;
                sx += Math.sin(a);
                sy += Math.cos(a);
            }
        }
        double frac = (double) cnt / n;
        if (frac < 0.12) {
            // 찬스뿌요(하얗게 바램)는 채도가 낮음 → 연한 색(분홍 제외) 화소가 가장 많은 색 계열로 보조 판정
            int[] cc = new int[4];   // Y G B P
            for (int k = 0; k < n; k++) {
                int hh = P.hh[k];
                if (hh >= 15 && hh < 155 && P.ss[k] > 45 && P.vv[k] > 150) {
                    char c = hueClass(hh);
                    cc[c == 'Y' ? 0 : c == 'G' ? 1 : c == 'B' ? 2 : 3]++;
                }
            }
            int best = -1, bc = 0;
            for (int q = 0; q < 4; q++) if (cc[q] > bc) { bc = cc[q]; best = q; }
            if (best >= 0 && (double) bc / n >= 0.15) return "YGBP".charAt(best);
            return '.';
        }
        double m = Math.atan2(sx / cnt, sy / cnt);
        m = ((m % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        return hueClass(m * 180 / (2 * Math.PI));
    }

    /** 프리즘볼: 색 계열 4가지 이상이 섞임(연한 프리즘 포함). 일반 뿌요·하트·찬스뿌요는 최대 2계열. */
    private static boolean isPrismPatch(Patch P) {
        int n = P.n;
        if (n == 0) return false;
        int[] cnt = new int[5];
        int tot = 0;
        for (int i = 0; i < n; i++) {
            if (P.ss[i] > 25 && P.vv[i] > 150) {
                cnt[classIdx(hueClass(P.hh[i]))]++;
                tot++;
            }
        }
        if (tot < n * 0.2) return false;
        int k = 0;
        for (int i = 0; i < 5; i++) if (cnt[i] >= tot * 0.06) k++;
        return k >= 4;
    }

    /** 방해뿌요: 무채색 몸통 + 어두운 눈 (하트는 분홍, 빈칸은 어두운 화소 없음) */
    private static boolean isGarbagePatch(Patch P) {
        int n = P.n;
        if (n == 0) return false;
        int gray = 0, dark = 0;
        for (int i = 0; i < n; i++) {
            if (P.ss[i] < 45 && P.vv[i] > 150) gray++;
            if (P.vv[i] < 110) dark++;
        }
        return (double) gray / n >= 0.4 && (double) dark / n >= 0.08;
    }

    private static boolean isHeartPatch(Patch P) {
        int n = P.n;
        if (n == 0) return false;
        int c = 0;
        for (int i = 0; i < n; i++) {
            int h = P.hh[i], s = P.ss[i], v = P.vv[i];
            if (h >= 155 && h <= 178 && s > 20 && s < 120 && v > 215) c++;
        }
        return (double) c / n > 0.5;
    }

    /* ------------------------------------------------------------ 격자 */
    public static Geom geometry(int w, int h, Calib cal) {
        if (cal == null) cal = new Calib();
        Geom g = new Geom();
        g.sx = (double) w / BASE_W;
        g.sy = (double) h / BASE_H;
        for (int c = 0; c < COLS; c++) g.xs[c] = (X_FIRST + cal.dx + c * CELL_W * cal.kx) * g.sx;
        for (int r = 0; r < ROWS; r++) g.ys[r] = (Y_FIRST + cal.dy + r * CELL_H * cal.ky) * g.sy;
        g.yp = g.ys[0] - (Y_FIRST - Y_PREVIEW) * g.sy * cal.ky;
        g.cw = CELL_W * cal.kx * g.sx;
        g.ch = CELL_H * cal.ky * g.sy;
        return g;
    }

    public static Board readBoard(PixelSource img, Calib cal) {
        Geom g = geometry(img.width(), img.height(), cal);
        Board b = new Board();
        b.geom = g;
        b.grid = new char[ROWS][COLS];
        b.preview = new char[COLS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Patch P = patchHSV(img, g.xs[c] - 38 * g.sx, g.ys[r] - 34 * g.sy, g.xs[c] + 38 * g.sx, g.ys[r] + 34 * g.sy);
                char col = classifyPatch(P);
                if (isPrismPatch(P)) col = 'X';
                else if (col == '.' && isHeartPatch(P)) col = 'H';
                else if (col == '.' && isGarbagePatch(P)) col = 'O';
                b.grid[r][c] = col;
            }
        }
        for (int c = 0; c < COLS; c++) {
            Patch Q = patchHSV(img, g.xs[c] - 14 * g.sx, g.yp - 14 * g.sy, g.xs[c] + 14 * g.sx, g.yp + 14 * g.sy);
            b.preview[c] = classifyPatch(Q);
        }
        return b;
    }

    private static int refSat(char ch) {
        switch (ch) {
            case 'R': return 176;
            case 'B': return 195;
            case 'G': return 183;
            case 'Y': return 178;
            case 'P': return 156;
            default: return -1;
        }
    }

    /** 찬스뿌요(하얗게 바랜 색뿌요) 후보 칸 [행,열]. 8칸 초과면 연출 오탐으로 보고 빈 목록. */
    public static List<int[]> detectChancePuyos(PixelSource img, char[][] grid, Calib cal) {
        Geom g = geometry(img.width(), img.height(), cal);
        List<int[]> found = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int ref = refSat(grid[r][c]);
                if (ref < 0) continue;
                int cx = (int) g.xs[c], cy = (int) g.ys[r];
                Patch P = patchHSV(img, cx - 45 * g.sx, cy - 45 * g.sy, cx + 45 * g.sx, cy + 45 * g.sy);
                int[] sats = new int[P.n], vals = new int[P.n];
                int m = 0;
                for (int i = 0; i < P.n; i++) {
                    if (P.vv[i] > 90 && P.ss[i] > 35) {
                        sats[m] = P.ss[i];
                        vals[m] = P.vv[i];
                        m++;
                    }
                }
                if (m < 400 * g.sx * g.sy) continue;
                java.util.Arrays.sort(sats, 0, m);
                java.util.Arrays.sort(vals, 0, m);
                double med = (m % 2 == 1) ? sats[(m - 1) / 2] : (sats[m / 2 - 1] + sats[m / 2]) / 2.0;
                int vm = vals[m >> 1];
                if (med < CHANCE_RATIO * ref && vm >= 215) found.add(new int[]{r, c});
            }
        }
        if (found.size() > CHANCE_MAX_CELLS) found.clear();
        return found;
    }

    /* ------------------------------------------------------------ "のこり N" 숫자 */
    public static Map<String, boolean[]> loadTemplates(String[][] pairs) {
        Map<String, boolean[]> t = new HashMap<>();
        for (String[] kv : pairs) t.put(kv[0], hexToBits(kv[1]));
        return t;
    }

    public static boolean[] hexToBits(String hex) {
        String bits = new BigInteger(hex, 16).toString(2).substring(1);   // 맨 앞 '1' 은 자리 맞춤용
        boolean[] a = new boolean[TPL_W * TPL_H];
        for (int i = 0; i < a.length; i++) a[i] = bits.charAt(i) == '1';
        return a;
    }

    public static String bitsToHex(boolean[] bits) {
        StringBuilder s = new StringBuilder("1");
        for (boolean b : bits) s.append(b ? '1' : '0');
        return new BigInteger(s.toString(), 2).toString(16);
    }

    private static boolean[] resizeBinary(boolean[] src, int sw, int sh) {
        boolean[] out = new boolean[TPL_W * TPL_H];
        for (int oy = 0; oy < TPL_H; oy++) {
            double y0 = (double) oy * sh / TPL_H, y1 = (double) (oy + 1) * sh / TPL_H;
            for (int ox = 0; ox < TPL_W; ox++) {
                double x0 = (double) ox * sw / TPL_W, x1 = (double) (ox + 1) * sw / TPL_W;
                double acc = 0, wsum = 0;
                for (int y = (int) Math.floor(y0); y < Math.ceil(y1) && y < sh; y++) {
                    double wy = Math.min(y + 1, y1) - Math.max(y, y0);
                    for (int x = (int) Math.floor(x0); x < Math.ceil(x1) && x < sw; x++) {
                        double wx = Math.min(x + 1, x1) - Math.max(x, x0);
                        if (src[y * sw + x]) acc += wx * wy;
                        wsum += wx * wy;
                    }
                }
                out[oy * TPL_W + ox] = wsum > 0 && (acc / wsum) * 255 > 127;
            }
        }
        return out;
    }

    private static final class Mask {
        boolean[] m;
        int w, h;
    }

    private static Mask digitMask(PixelSource img) {
        double sx = (double) img.width() / BASE_W, sy = (double) img.height() / BASE_H;
        Patch P = patchHSV(img, DIGIT_ROI[0] * sx, DIGIT_ROI[1] * sy, DIGIT_ROI[2] * sx, DIGIT_ROI[3] * sy);
        Mask M = new Mask();
        M.w = P.w;
        M.h = P.h;
        M.m = new boolean[P.n];
        for (int i = 0; i < P.n; i++) {
            int h = P.hh[i], s = P.ss[i], v = P.vv[i];
            M.m[i] = (h >= 15 && h <= 40 && s > 100 && v > 150) || (s < 70 && v > 190);
        }
        return M;
    }

    private static List<boolean[]> groups(Mask M) {
        int w = M.w, h = M.h;
        boolean[] cols = new boolean[w];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) if (M.m[y * w + x]) { cols[x] = true; break; }
        }
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int x = 0; x < w + 3; x++) {
            boolean cx = x < w && cols[x];
            if (cx && start < 0) start = x;
            if (!cx && start >= 0) {
                boolean n1 = (x + 0 < w) && cols[x + 0];
                boolean n2 = (x + 1 < w) && cols[x + 1];
                if (!n1 && !n2) { runs.add(new int[]{start, x}); start = -1; }   // 2칸 이상 비어야 분리
            }
        }
        List<boolean[]> out = new ArrayList<>();
        for (int[] rn : runs) {
            int x0 = rn[0], x1 = rn[1], pw = x1 - x0, ymin = h, ymax = -1, cnt = 0;
            for (int y = 0; y < h; y++) {
                for (int x = x0; x < x1; x++) {
                    if (M.m[y * w + x]) { cnt++; if (y < ymin) ymin = y; if (y > ymax) ymax = y; }
                }
            }
            int ph = ymax - ymin + 1;
            if (pw < 4 || ph < 10 || cnt < 40) continue;
            boolean[] crop = new boolean[pw * ph];
            for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) crop[y * pw + x] = M.m[(ymin + y) * w + x0 + x];
            out.add(resizeBinary(crop, pw, ph));
        }
        return out;
    }

    /** 학습용: 지금 화면 "のこり" 숫자 글자들 */
    public static List<boolean[]> remainingGlyphs(PixelSource img) {
        return groups(digitMask(img));
    }

    private static double matchBest(boolean[] bits, Map<String, boolean[]> tpls, String[] bestOut) {
        double bestS = 0;
        bestOut[0] = null;
        for (Map.Entry<String, boolean[]> e : tpls.entrySet()) {
            boolean[] t = e.getValue();
            int inter = 0, uni = 0;
            for (int i = 0; i < bits.length; i++) {
                if (bits[i] && t[i]) inter++;
                if (bits[i] || t[i]) uni++;
            }
            double s = uni > 0 ? (double) inter / uni : 0;
            if (s > bestS) { bestS = s; bestOut[0] = e.getKey(); }
        }
        return bestS;
    }

    /** "のこり N コ" 의 N. 못 읽으면 n = -1 */
    public static Digit readRemaining(PixelSource img, Map<String, boolean[]> tpls) {
        Digit d = new Digit();
        if (tpls == null || tpls.isEmpty()) return d;
        List<boolean[]> gs = groups(digitMask(img));
        if (gs.isEmpty() || gs.size() > 2) return d;
        int val = 0;
        double worst = 1;
        String[] key = new String[1];
        for (boolean[] g : gs) {
            double s = matchBest(g, tpls, key);
            worst = Math.min(worst, s);
            if (key[0] == null || s < MIN_SCORE) { d.score = worst; return d; }
            val = val * 10 + Integer.parseInt(key[0]);
        }
        d.n = val;
        d.score = worst;
        return d;
    }
}
