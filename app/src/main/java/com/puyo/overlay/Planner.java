package com.puyo.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 훑기 플래너 (planner.js 와 동일 로직의 Java 판; 일반 모드만).
 * 칸 값: 0 빈칸, 1 R, 2 B, 3 G, 4 Y, 5 P, 6 하트, 7 미지(벽), 8 프리즘, 9 방해뿌요. +16 = 찬스뿌요 표시.
 */
public final class Planner {
    public static final int ROWS = 6, COLS = 8, NC = 48;
    static final double[] POT_F = {0.0, 0.0, 1.0, 3.0, 10.0};
    static final double JOIN_Q = 0.2;
    public static final double[] DEFAULT_CHAIN_MULT = {1.0, 1.6, 2.4, 3.4, 4.6, 6.0, 7.6, 9.4, 11.4, 13.6};
    static final double DEFAULT_POT_W = 0.4;

    static final int[] NB4 = new int[NC * 4];
    static final int[][] N8 = new int[NC][];
    static final int[][] N8P = new int[NC][];

    static {
        Arrays.fill(NB4, -1);
        int[][] d4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[][] d8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int i = r * COLS + c, k = 0;
                for (int[] d : d4) {
                    int y = r + d[0], x = c + d[1];
                    if (y >= 0 && y < ROWS && x >= 0 && x < COLS) NB4[i * 4 + k++] = y * COLS + x;
                }
                List<Integer> a = new ArrayList<>();
                for (int[] d : d8) {
                    int y = r + d[0], x = c + d[1];
                    if (y >= 0 && y < ROWS && x >= 0 && x < COLS) a.add(y * COLS + x);
                }
                N8P[i] = new int[a.size()];
                for (int q = 0; q < a.size(); q++) N8P[i][q] = a.get(q);
                N8[i] = N8P[i];
            }
        }
    }

    private Planner() {}

    /** 입력 */
    public static final class Job {
        public byte[] board = new byte[NC];
        public int[] preview = new int[COLS];
        public double[] deckW = new double[16];
        public double[] mult = DEFAULT_CHAIN_MULT;
        public double healW, potW = DEFAULT_POT_W, chanceW, prismW;
        public int limit = 5, topK = 8;
        public boolean preFirst;
    }

    /** 후보 하나 */
    public static final class Item {
        public long key, mask;
        public int pc, pre, post;
        public double score;
        public int[] cells, path;
    }

    static final Comparator<Item> ORDER = new Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            if (a.key != b.key) return a.key > b.key ? -1 : 1;
            if (a.pc != b.pc) return a.pc < b.pc ? -1 : 1;
            return a.mask < b.mask ? -1 : (a.mask > b.mask ? 1 : 0);
        }
    };

    /* ------------------------------------------------------------ 경로 */
    public static int[] findPath(int[] cl) {
        final int n = cl.length;
        int[] sorted = cl.clone();
        Arrays.sort(sorted);
        boolean[] left = new boolean[NC];
        int[] path = new int[n];
        for (int s = 0; s < n; s++) {
            Arrays.fill(left, false);
            for (int q = 0; q < n; q++) left[sorted[q]] = true;
            left[sorted[s]] = false;
            path[0] = sorted[s];
            if (dfs(1, n, left, path)) return path;
        }
        return null;
    }

    private static boolean dfs(int d, int n, boolean[] left, int[] path) {
        if (d == n) return true;
        int[] nb = N8P[path[d - 1]];
        for (int k = 0; k < nb.length; k++) {
            int t = nb[k];
            if (left[t]) {
                left[t] = false;
                path[d] = t;
                if (dfs(d + 1, n, left, path)) return true;
                left[t] = true;
            }
        }
        return false;
    }

    static long maskOf(int[] cells, int n) {
        long m = 0;
        for (int q = 0; q < n; q++) m |= 1L << cells[q];
        return m;
    }

    /* ------------------------------------------------------------ 탐색기 (스레드마다 하나) */
    static final class Searcher {
        final Job job;
        final byte[] board;
        final int[] preview;
        final double[] deckW, mult;
        final double healW, potW, chanceW, prismW;
        final int limit, K, mlen;
        final byte[] b = new byte[NC];
        final boolean[] seen = new boolean[NC], erase = new boolean[NC], eraseX = new boolean[NC];
        final int[] stack = new int[NC], grp = new int[NC], stamp = new int[NC];
        final boolean[] allowed = new boolean[NC];
        double rTotal;
        int rSteps;
        double oScore;
        long oKey;
        int oC1, oC2;
        final int[] cells = new int[16];
        final int[] mark = new int[NC];
        final int[][] bufs = new int[16][64];
        int root;
        List<Item> top;
        long deadline = Long.MAX_VALUE;      // System.nanoTime 기준
        AtomicBoolean timedOut = new AtomicBoolean(false);
        int tick;

        Searcher(Job j) {
            job = j;
            board = j.board;
            preview = j.preview;
            deckW = j.deckW;
            mult = j.mult;
            healW = j.healW;
            potW = j.potW;
            chanceW = j.chanceW;
            prismW = j.prismW;
            limit = j.limit;
            K = j.topK > 0 ? j.topK : 8;
            mlen = mult.length;
            for (int i = 0; i < NC; i++) allowed[i] = (board[i] & 15) != 9;
        }

        void gravity() {
            for (int c = 0; c < COLS; c++) {
                int w = 5;
                for (int r = 5; r >= 0; r--) {
                    byte v = b[r * 8 + c];
                    if (v != 0) {
                        b[w * 8 + c] = v;
                        w--;
                    }
                }
                while (w >= 0) {
                    b[w * 8 + c] = 0;
                    w--;
                }
            }
        }

        void runChain(int steps, double total) {
            for (;;) {
                for (int i = 0; i < NC; i++) {
                    seen[i] = false;
                    erase[i] = false;
                    eraseX[i] = false;
                }
                int nErase = 0;
                double gain = 0.0;
                int k = steps < mlen - 1 ? steps : mlen - 1;
                double mm = mult[k];
                for (int i = 0; i < NC; i++) {
                    int col = b[i] & 15;
                    if (col == 0 || col > 5 || seen[i]) continue;
                    seen[i] = true;
                    int sp = 0;
                    stack[sp++] = i;
                    int gl = 0;
                    while (sp > 0) {
                        int j = stack[--sp];
                        grp[gl++] = j;
                        for (int q = 0; q < 4; q++) {
                            int t = NB4[j * 4 + q];
                            if (t >= 0 && !seen[t] && (b[t] & 15) == col) {
                                seen[t] = true;
                                stack[sp++] = t;
                            }
                        }
                    }
                    if (gl >= 4) {
                        gain += deckW[col] * gl * mm;
                        for (int q = 0; q < gl; q++) {
                            erase[grp[q]] = true;
                            if (b[grp[q]] >= 16) gain += chanceW;
                        }
                        nErase += gl;
                    }
                }
                if (nErase == 0) {
                    rTotal = total;
                    rSteps = steps;
                    return;
                }
                for (int i = 0; i < NC; i++) {
                    if (!erase[i]) continue;
                    for (int q2 = 0; q2 < 4; q2++) {
                        int t2 = NB4[i * 4 + q2];
                        if (t2 < 0 || erase[t2] || eraseX[t2]) continue;
                        int c2 = b[t2] & 15;
                        if (c2 == 8) {
                            eraseX[t2] = true;
                            gain += prismW * mm;
                        } else if (c2 == 6) {
                            eraseX[t2] = true;
                            gain += healW;
                        } else if (c2 == 9) {
                            eraseX[t2] = true;
                        }
                    }
                }
                total += gain;
                for (int i = 0; i < NC; i++) if (erase[i] || eraseX[i]) b[i] = 0;
                steps++;
                gravity();
            }
        }

        double potential() {
            for (int i = 0; i < NC; i++) {
                seen[i] = false;
                stamp[i] = 0;
            }
            double pot = 0.0;
            int gid = 0;
            for (int i = 0; i < NC; i++) {
                int col = b[i] & 15;
                if (col == 0 || col > 5 || seen[i]) continue;
                seen[i] = true;
                int sp = 0;
                stack[sp++] = i;
                int gl = 0;
                while (sp > 0) {
                    int j = stack[--sp];
                    grp[gl++] = j;
                    for (int q = 0; q < 4; q++) {
                        int t = NB4[j * 4 + q];
                        if (t >= 0 && !seen[t] && (b[t] & 15) == col) {
                            seen[t] = true;
                            stack[sp++] = t;
                        }
                    }
                }
                double w = deckW[col];
                if (w == 0.0) continue;
                gid++;
                int adj = 0;
                for (int q = 0; q < gl; q++) {
                    for (int r = 0; r < 4; r++) {
                        int t2 = NB4[grp[q] * 4 + r];
                        if (t2 >= 0 && b[t2] == 7 && stamp[t2] != gid) {
                            stamp[t2] = gid;
                            adj++;
                        }
                    }
                }
                int s = gl < 4 ? gl : 4;
                int s2 = s < 4 ? s + 1 : 4;
                pot += w * (POT_F[s] + JOIN_Q * adj * (POT_F[s2] - POT_F[s]));
            }
            return pot;
        }

        void evalNormal(int n) {
            System.arraycopy(board, 0, b, 0, NC);
            int heal = 0;
            for (int q = 0; q < n; q++) {
                int ci = cells[q];
                if ((b[ci] & 15) == 6) heal++;
                b[ci] = 0;
            }
            gravity();
            runChain(0, 0.0);
            double total = rTotal;
            int steps1 = rSteps;
            for (int c = 0; c < COLS; c++) {
                int e = 0;
                while (e < 6 && b[e * 8 + c] == 0) e++;
                if (e > 0) {
                    int pv = preview[c];
                    b[(e - 1) * 8 + c] = (byte) ((pv >= 1 && pv <= 6) ? pv : 7);
                    for (int r = 0; r < e - 1; r++) b[r * 8 + c] = 7;
                }
            }
            runChain(steps1, total);
            total = rTotal;
            int stepsAll = rSteps;
            for (int i = 0; i < NC; i++) if (b[i] == 0) b[i] = 7;
            double pot = potential();
            oScore = total + potW * pot + heal * healW;
            oC1 = steps1;
            oC2 = stepsAll - steps1;
            oKey = Math.round(oScore * 1e6);
            if (job.preFirst) oKey += oC1 * 100000000000L;
        }

        boolean better(long key, int n, Item w) {
            if (key > w.key) return true;
            if (key < w.key) return false;
            if (n < w.pc) return true;
            if (n > w.pc) return false;
            return maskOf(cells, n) < w.mask;
        }

        void consider(int n) {
            evalNormal(n);
            int tl = top.size();
            if (tl >= K && !better(oKey, n, top.get(tl - 1))) return;
            int[] cl = Arrays.copyOf(cells, n);
            int[] path = findPath(cl);
            if (path == null) return;
            Item item = new Item();
            item.key = oKey;
            item.pc = n;
            item.mask = maskOf(cells, n);
            item.score = oScore;
            Arrays.sort(cl);
            item.cells = cl;
            item.path = path;
            item.pre = oC1;
            item.post = oC2;
            int p = tl;
            while (p > 0 && ORDER.compare(item, top.get(p - 1)) < 0) p--;
            top.add(p, item);
            if (top.size() > K) top.remove(top.size() - 1);
        }

        void rec(int n, int[] ext, int extLen) {
            if ((++tick & 1023) == 0 && System.nanoTime() > deadline) timedOut.set(true);
            if (timedOut.get()) return;
            consider(n);
            if (n >= limit) return;
            int[] next = bufs[n];
            while (extLen > 0) {
                int w = ext[--extLen];
                int nl = extLen;
                for (int i = 0; i < extLen; i++) next[i] = ext[i];
                int[] nb = N8[w];
                int q;
                for (q = 0; q < nb.length; q++) {
                    int u = nb[q];
                    if (u > root && mark[u] == 0 && allowed[u]) next[nl++] = u;
                }
                cells[n] = w;
                mark[w]++;
                for (q = 0; q < nb.length; q++) mark[nb[q]]++;
                rec(n + 1, next, nl);
                mark[w]--;
                for (q = 0; q < nb.length; q++) mark[nb[q]]--;
            }
        }

        List<Item> searchRoot(int r) {
            root = r;
            top = new ArrayList<>();
            if (!allowed[r]) return top;
            Arrays.fill(mark, 0);
            cells[0] = r;
            mark[r]++;
            int[] nb = N8[r];
            int[] ext0 = new int[16];
            int el = 0, q;
            for (q = 0; q < nb.length; q++) mark[nb[q]]++;
            for (q = 0; q < nb.length; q++) if (nb[q] > r && allowed[nb[q]]) ext0[el++] = nb[q];
            rec(1, ext0, el);
            return top;
        }
    }

    /** 한 root 만 (테스트/단일 스레드용) */
    public static List<Item> searchRoot(Job job, int root) {
        return new Searcher(job).searchRoot(root);
    }

    /** 시간 제한 탐색 결과 */
    public static final class Plan {
        public List<Item> items;
        public int doneLimit;      // 끝까지 탐색 완료한 최대 칸 수
        public boolean cut;        // 시간 제한으로 요청한 칸 수까지 못 갔는지
    }

    /**
     * 칸 수를 6부터 차례로 늘려 가며 탐색하고, 시간 예산이 끝나면 마지막으로 끝까지 마친 단계의 결과를 돌려준다.
     * (칸 수가 크면 후보가 폭발적으로 늘어 몇 초 안에 못 끝날 수 있음)
     */
    public static Plan planBudget(Job job, int n, int threads, long budgetMs) {
        final int want = job.limit;
        long deadline = System.nanoTime() + budgetMs * 1000000L;
        Plan out = new Plan();
        for (int l = Math.min(want, 6); l <= want; l++) {
            job.limit = l;
            AtomicBoolean to = new AtomicBoolean(false);
            List<Item> r = planImpl(job, n, threads, deadline, to);
            if (to.get()) { out.cut = true; break; }
            out.items = r;
            out.doneLimit = l;
        }
        job.limit = want;
        if (out.items == null) {   // 6칸조차 못 끝낸 극단적인 경우
            job.limit = Math.min(want, 4);
            out.items = planImpl(job, n, threads, Long.MAX_VALUE, new AtomicBoolean(false));
            out.doneLimit = job.limit;
            job.limit = want;
        }
        return out;
    }

    /** 전체 탐색 후 상위 n 개 (점수 ↓, 칸 수 ↑, 마스크 ↑) */
    public static List<Item> plan(final Job job, int n, int threads) {
        return planImpl(job, n, threads, Long.MAX_VALUE, new AtomicBoolean(false));
    }

    static List<Item> planImpl(final Job job, int n, int threads, final long deadline, final AtomicBoolean timedOut) {
        final List<Item> all = Collections.synchronizedList(new ArrayList<Item>());
        if (threads <= 1) {
            Searcher s = new Searcher(job);
            s.deadline = deadline;
            s.timedOut = timedOut;
            for (int r = 0; r < NC && !timedOut.get(); r++) all.addAll(s.searchRoot(r));
        } else {
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            final AtomicInteger next = new AtomicInteger(0);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(ex.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        Searcher s = new Searcher(job);
                        s.deadline = deadline;
                        s.timedOut = timedOut;
                        int r;
                        while (!timedOut.get() && (r = next.getAndIncrement()) < NC) all.addAll(s.searchRoot(r));
                        return null;
                    }
                }));
            }
            try {
                for (Future<?> f : fs) f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                ex.shutdown();
            }
        }
        List<Item> copy = new ArrayList<>(all);
        Collections.sort(copy, ORDER);
        return copy.size() > n ? new ArrayList<>(copy.subList(0, n)) : copy;
    }

    /* ------------------------------------------------------------ 입력 만들기 */
    public static int code(char ch) {
        switch (ch) {
            case '.': return 0;
            case 'R': return 1;
            case 'B': return 2;
            case 'G': return 3;
            case 'Y': return 4;
            case 'P': return 5;
            case 'H': return 6;
            case 'X': return 8;
            case 'O': return 9;
            default: return 7;
        }
    }

    /** weights: R,B,G,Y,P 순서 5개. chance: [행,열] 목록(없으면 null) */
    public static Job buildJob(char[][] grid, char[] preview, double[] weights, double heal, double prismW,
                               double chanceW, int limit, boolean preFirst, List<int[]> chance) {
        Job j = new Job();
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) j.board[r * COLS + c] = (byte) code(grid[r][c]);
        for (int c = 0; c < COLS; c++) {
            char p = preview == null ? 0 : preview[c];
            j.preview[c] = "RBGYP".indexOf(p) >= 0 && p != 0 ? code(p) : 0;
        }
        for (int q = 0; q < 5; q++) j.deckW[q + 1] = weights[q];
        if (chance != null) {
            for (int[] rc : chance) {
                int i = rc[0] * COLS + rc[1];
                if (j.board[i] >= 1 && j.board[i] <= 5) j.board[i] += 16;
            }
        }
        j.healW = heal;
        j.prismW = prismW;
        j.chanceW = chanceW;
        j.limit = limit;
        j.preFirst = preFirst;
        return j;
    }
}
