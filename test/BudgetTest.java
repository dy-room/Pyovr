import com.puyo.overlay.*;
public class BudgetTest {
    public static void main(String[] a) {
        char[][] g = new char[6][8]; String C = "RBGYP"; java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 6; i++) for (int j = 0; j < 8; j++) g[i][j] = C.charAt(r.nextInt(5));
        char[] pv = "RBGYPRBG".toCharArray();
        for (int L : new int[]{1, 5, 8, 15}) {
            Planner.Job j = Planner.buildJob(g, pv, new double[]{3, .4, .4, .4, .4}, 0, 6, 30, L, false, null);
            long t = System.currentTimeMillis();
            Planner.Plan p = Planner.planBudget(j, 3, 4, 6000);
            System.out.println("L=" + L + " done=" + p.doneLimit + " cut=" + p.cut + " items=" + p.items.size() + " ms=" + (System.currentTimeMillis() - t));
        }
    }
}
