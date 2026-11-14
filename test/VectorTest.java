import com.puyo.overlay.*;
import java.nio.file.*;
import java.util.*;

public class VectorTest {
    static int bad = 0, total = 0;

    static String cl(int[] a) { StringBuilder s = new StringBuilder(); for (int i = 0; i < a.length; i++) { if (i > 0) s.append(','); s.append(a[i]); } return s.toString(); }

    static List<int[]> parseChance(String s) {
        List<int[]> l = new ArrayList<>();
        if (s.equals("-")) return l;
        for (String p : s.split(";")) { String[] q = p.split(","); l.add(new int[]{Integer.parseInt(q[0]), Integer.parseInt(q[1])}); }
        return l;
    }

    static char[][] grid(String s) { char[][] g = new char[6][8]; for (int i = 0; i < 48; i++) g[i / 8][i % 8] = s.charAt(i); return g; }

    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("planner_vectors.txt"));
        Planner.Job job = null; List<String> exp = new ArrayList<>(); int ci = 0;
        for (String ln : lines) {
            String[] f = ln.split(" ");
            if (f[0].equals("C")) {
                String[] w = f[3].split(",");
                double[] wt = new double[5]; for (int i = 0; i < 5; i++) wt[i] = Double.parseDouble(w[i]);
                char[] pv = f[2].toCharArray();
                job = Planner.buildJob(grid(f[1]), pv, wt, Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                        Integer.parseInt(f[7]), f[8].equals("1"), parseChance(f[9]), Double.parseDouble(f[10]), f[11].equals("1"));
                exp.clear();
            } else if (f[0].equals("T")) exp.add(ln);
            else if (f[0].equals("E")) {
                for (int th : new int[]{1, 4}) {
                    List<Planner.Item> top = Planner.plan(job, 3, th);
                    for (int i = 0; i < exp.size(); i++) {
                        total++;
                        String[] e = exp.get(i).split(" ");
                        Planner.Item g = i < top.size() ? top.get(i) : null;
                        boolean ok = g != null && Math.abs(g.score - Double.parseDouble(e[1])) < 1e-6 && g.pre == Integer.parseInt(e[2])
                                && g.post == Integer.parseInt(e[3]) && cl(g.cells).equals(e[4]) && cl(g.path).equals(e[5]);
                        if (!ok) { bad++; System.out.println("MISMATCH case " + ci + " rank " + i + " threads " + th); }
                    }
                }
                ci++;
            }
        }
        System.out.println("planner compared " + total + " ranks, mismatches " + bad);

        Map<String, boolean[]> tpl = Reader.loadTemplates(com.puyo.overlay.Templates.REMAIN);
        int rbad = 0;
        for (String ln : Files.readAllLines(Paths.get("reader_vectors.txt"))) {
            String[] f = ln.split(" ");
            if (!f[0].equals("R")) continue;
            final int w = Integer.parseInt(f[2]), h = Integer.parseInt(f[3]);
            final byte[] buf = Files.readAllBytes(Paths.get(f[1]));
            PixelSource src = new PixelSource() {
                public int width() { return w; }
                public int height() { return h; }
                public int rgb(int x, int y) { int o = (y * w + x) * 4; return ((buf[o] & 255) << 16) | ((buf[o + 1] & 255) << 8) | (buf[o + 2] & 255); }
            };
            Reader.Calib cal = Reader.autoCalib(src);
            int ady = Reader.autoDy(src);
            Reader.Board b = Reader.readBoard(src, cal);
            StringBuilder gs = new StringBuilder(); for (char[] r : b.grid) gs.append(r);
            List<int[]> ch = Reader.detectChancePuyos(src, b.grid, cal);
            StringBuilder cs = new StringBuilder(); for (int[] x : ch) { if (cs.length() > 0) cs.append(';'); cs.append(x[0]).append(',').append(x[1]); }
            if (cs.length() == 0) cs.append('-');
            Reader.Digit d = Reader.readRemaining(src, tpl);
            boolean ok = gs.toString().equals(f[4]) && new String(b.preview).equals(f[5]) && cs.toString().equals(f[6]) && d.n == Integer.parseInt(f[7]) && (ady == Integer.MIN_VALUE ? -9999 : ady) == Integer.parseInt(f[8]);
            System.out.println(f[1] + " " + (ok ? "OK" : "FAIL " + gs + " " + new String(b.preview) + " " + cs + " " + d.n));
            if (!ok) rbad++;
        }
        System.out.println("reader bad " + rbad);
        System.exit(bad + rbad == 0 ? 0 : 1);
    }
}
