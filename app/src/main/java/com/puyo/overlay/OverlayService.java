package com.puyo.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * 화면 캡처(MediaProjection) → 인식 → 플래너 → 오버레이 표시.
 * 분석 때마다 오버레이를 잠깐 숨겨 깨끗한 게임 화면을 읽는다.
 */
public class OverlayService extends Service {
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACTION_STOP = "stop";
    static volatile boolean running = false;

    private static final int NOTI_ID = 7;
    private static final long AUTO_PERIOD_MS = 900;

    private WindowManager wm;
    private PathView pathView;
    private LinearLayout panel;
    private TextView btnAnalyze, btnNext, btnMode;
    private WindowManager.LayoutParams panelLp;

    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private ImageReader reader;
    private int sw, sh;

    private Handler ui;
    private HandlerThread workThread;
    private Handler work;
    private Prefs prefs;
    private Map<String, boolean[]> tpl;

    private volatile boolean wantFrame;
    private final Object frameLock = new Object();
    private volatile boolean busy;

    private List<Planner.Item> results;
    private int shown;
    private Reader.Geom lastGeom;
    private String lastHead = "";
    private String lastSig = "";
    private String pendingSig = "";
    private Reader.Board lastBoard;
    private List<int[]> lastChance;
    private int lastNRead;
    private TextView nLabel;
    private boolean collapsed;
    private TextView btnEye;
    private LinearLayout row1Ref, row2Ref;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNoti();   // startForegroundService 로 시작됐으면 반드시 호출해야 함
        if (running || intent == null) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ui = new Handler(Looper.getMainLooper());
        workThread = new HandlerThread("puyo-work");
        workThread.start();
        work = new Handler(workThread.getLooper());
        prefs = new Prefs(this);
        tpl = Reader.loadTemplates(Templates.REMAIN);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        try {
            setupCapture(code, data);
            setupOverlay();
        } catch (Exception e) {
            Toast.makeText(this, "시작 실패: " + e, Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        refreshModeLabel();
        refreshN();
        if (prefs.mode() == Prefs.MODE_AUTO) ui.postDelayed(autoTick, 1500);
        return START_NOT_STICKY;
    }

    private void startForegroundNoti() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("cap", "화면 캡처", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "cap") : new Notification.Builder(this);
        nb.setContentTitle("뿌요 오버레이 실행 중").setContentText("화면 위 ✕ 버튼으로 종료")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true);
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    /* ------------------------------------------------------------ 캡처 */
    private void setupCapture(int code, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        // Android 14+: createVirtualDisplay 전에 콜백 등록 필수
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                ui.post(new Runnable() { public void run() { stopSelf(); } });
            }
        }, ui);
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
            sw = b.width();
            sh = b.height();
            dm.densityDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels;
            sh = dm.heightPixels;
        }
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                synchronized (frameLock) { frameLock.notifyAll(); }
                if (!wantFrame) {   // 요청이 없으면 프레임을 바로 버려 큐가 막히지 않게 함
                    Image im = null;
                    try { im = r.acquireLatestImage(); } catch (Exception ignore) { }
                    if (im != null) im.close();
                }
            }
        }, work);
        vdisplay = projection.createVirtualDisplay("puyo-cap", sw, sh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
    }

    /** 오버레이를 숨긴 뒤 새 프레임 한 장을 받는다 (작업 스레드에서 호출). */
    private Image grabClean() {
        wantFrame = true;
        try {
            ui.post(new Runnable() { public void run() { setOverlayVisible(false); } });
            Image im = null;
            long end = System.currentTimeMillis() + 1200;
            // 숨김이 화면에 반영된 뒤의 프레임을 쓰기 위해 먼저 잠깐 기다린 다음 가장 최근 프레임 사용
            sleep(180);
            while (System.currentTimeMillis() < end) {
                try { im = reader.acquireLatestImage(); } catch (Exception e) { im = null; }
                if (im != null) return im;
                synchronized (frameLock) {
                    try { frameLock.wait(100); } catch (InterruptedException e) { return null; }
                }
            }
            return null;
        } finally {
            wantFrame = false;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) { }
    }

    /* ------------------------------------------------------------ 분석 */
    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (prefs.mode() == Prefs.MODE_AUTO) {
                if (!collapsed) analyze(true);
                ui.postDelayed(this, AUTO_PERIOD_MS);
            }
        }
    };

    private void analyze(final boolean auto) {
        if (busy) return;
        busy = true;
        work.post(new Runnable() {
            @Override
            public void run() {
                try {
                    doAnalyze(auto);
                } catch (Throwable t) {
                    toast("분석 오류: " + t);
                } finally {
                    ui.post(new Runnable() { public void run() { setOverlayVisible(true); } });
                    busy = false;
                }
            }
        });
    }

    private void doAnalyze(boolean auto) {
        Image im = grabClean();
        if (im == null) {
            if (!auto) toast("화면을 가져오지 못했어요");
            return;
        }
        Reader.Board board;
        List<int[]> chance;
        int nRead;
        try {
            Image.Plane pl = im.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            PixelSource src = new BufferSource(buf, im.getWidth(), im.getHeight(), pl.getRowStride(), pl.getPixelStride());
            board = Reader.readBoard(src, null);
            chance = Reader.detectChancePuyos(src, board.grid, null);
            nRead = Reader.readRemaining(src, tpl).n;
        } finally {
            im.close();
        }
        ui.post(new Runnable() { public void run() { setOverlayVisible(true); } });   // 캡처가 끝났으니 계산 중에는 다시 보이게
        // 오버레이는 곧 다시 보이게 하되, 자동 모드에서는 바뀐 게 없으면 다시 그리지 않음
        StringBuilder sg = new StringBuilder();
        for (char[] r : board.grid) sg.append(r);
        sg.append('|').append(board.preview).append('|').append(nRead).append('|').append(prefs.manualN());
        final String sig = sg.toString();
        if (auto) {
            if (!sig.equals(pendingSig)) {   // 두 번 연속 같은 화면일 때만 (연출 중 오인식 방지)
                pendingSig = sig;
                return;
            }
            if (sig.equals(lastSig) && results != null) return;
        }
        pendingSig = sig;
        lastSig = sig;
        boolean empty = true;
        for (char[] r : board.grid) for (char c : r) if (c != '.') empty = false;
        if (empty) {
            results = null;
            lastBoard = null;
            ui.post(new Runnable() { public void run() { pathView.clear(); } });
            return;
        }
        lastBoard = board;
        lastChance = chance;
        lastNRead = nRead;
        replan();
    }

    /** 마지막으로 읽은 보드를 현재 설정(칸 수 등)으로 다시 계산 (작업 스레드). */
    private void replan() {
        Reader.Board board = lastBoard;
        if (board == null) return;
        int n = (prefs.autoN() && lastNRead > 0) ? lastNRead : prefs.manualN();
        n = Math.max(1, Math.min(Prefs.MAX_N, n));
        double[] w = new double[5];
        int deck = prefs.deck();
        for (int i = 0; i < 5; i++) w[i] = (deck == 0 || (deck & (1 << i)) != 0) ? prefs.mainW() : prefs.otherW();
        double prismW = prefs.mainW() * 2;
        Planner.Job job = Planner.buildJob(board.grid, board.preview, w, 0, prismW, 30, n, prefs.preFirst(), lastChance, prefs.traceScore() ? 1.0 : 0.0, true);
        int th = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        long t0 = System.currentTimeMillis();
        Planner.Plan pl = Planner.planBudget(job, 3, th, 6000);
        long dt = System.currentTimeMillis() - t0;
        results = pl.items;
        shown = 0;
        lastGeom = board.geom;
        lastHead = "N=" + n + (prefs.autoN() && lastNRead > 0 ? "(인식)" : "")
                + (pl.cut ? " ⚠시간제한: " + pl.doneLimit + "칸까지만 탐색" : " · " + dt + "ms");
        postShow();
    }

    private void postShow() {
        final List<Planner.Item> r = results;
        if (r == null || r.isEmpty()) {
            ui.post(new Runnable() { public void run() { pathView.clear(); } });
            return;
        }
        final Planner.Item it = r.get(shown);
        final String head = (shown + 1) + "위 · " + it.pc + "칸 · 연쇄 " + (it.pre + it.post) + (it.post > 0 ? " (확정 " + it.pre + ")" : "")
                + " · " + lastHead;
        final Reader.Geom g = lastGeom;
        ui.post(new Runnable() { public void run() { pathView.show(g, it, head); } });
    }

    /* ------------------------------------------------------------ 오버레이 UI */
    private void setupOverlay() {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        pathView = new PathView(this, prefs);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(pathView, lp);

        float d = getResources().getDisplayMetrics().density;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB0202124);
        bg.setCornerRadius(18 * d);
        panel.setBackground(bg);
        prefListener = new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) { if (pathView != null) pathView.postInvalidate(); }
        };
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener);
        panel.setPadding((int) (4 * d), (int) (2 * d), (int) (4 * d), (int) (2 * d));

        TextView grip = mkBtn("⠿");
        btnEye = mkBtn("👁");
        TextView btnMinus = mkBtn("−");
        nLabel = mkBtn("");
        nLabel.setMinWidth((int) (28 * d));
        TextView btnPlus = mkBtn("＋");
        btnAnalyze = mkBtn("분석");
        btnNext = mkBtn("다음안");
        btnMode = mkBtn("");
        TextView btnClose = mkBtn("✕");
        row1.addView(grip);
        row1.addView(btnEye);
        row1.addView(btnMinus);
        row1.addView(nLabel);
        row1.addView(btnPlus);
        row1.addView(btnAnalyze);
        row1.addView(btnNext);
        row1.addView(btnMode);
        row1.addView(btnClose);
        row1Ref = row1;
        row2Ref = row2;
        panel.addView(row1);
        buildDeckChips(row2);
        panel.addView(row2);

        btnEye.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setCollapsed(!collapsed); } });
        btnMinus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { changeN(-1); } });
        btnPlus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { changeN(1); } });
        btnAnalyze.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { analyze(false); } });
        btnNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (results == null || results.isEmpty()) return;
                shown = (shown + 1) % results.size();
                postShow();
            }
        });
        btnMode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean toAuto = prefs.mode() != Prefs.MODE_AUTO;
                prefs.setMode(toAuto ? Prefs.MODE_AUTO : Prefs.MODE_BUTTON);
                refreshModeLabel();
                ui.removeCallbacks(autoTick);
                if (toAuto) ui.postDelayed(autoTick, 500);
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stopSelf(); } });

        panelLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = (int) (8 * d);
        panelLp.y = (int) (90 * d);
        wm.addView(panel, panelLp);

        grip.setOnTouchListener(new View.OnTouchListener() {
            float ox, oy;
            int px, py;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ox = e.getRawX(); oy = e.getRawY(); px = panelLp.x; py = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = px + (int) (e.getRawX() - ox);
                        panelLp.y = py + (int) (e.getRawY() - oy);
                        wm.updateViewLayout(panel, panelLp);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    /** 한 번에 훑을 칸 수 ±1 (1~15). 직접 조절하면 のこり 자동 인식은 끈다. */
    private void changeN(int d) {
        int cur = prefs.manualN();
        if (prefs.autoN() && lastNRead > 0) cur = lastNRead;
        prefs.setAutoN(false);
        prefs.setManualN(cur + d);
        refreshN();
        replanAsync();
    }

    private static final int[] DECK_COL = {0xFFFF5252, 0xFF448AFF, 0xFF4CAF50, 0xFFFFD740, 0xFFB388FF};
    private final TextView[] chips = new TextView[5];

    private void buildDeckChips(LinearLayout row) {
        float d = getResources().getDisplayMetrics().density;
        TextView lab = mkBtn("속성");
        lab.setTextSize(12);
        row.addView(lab);
        for (int i = 0; i < 5; i++) {
            final int bit = 1 << i;
            TextView c = mkBtn("●");
            c.setTextColor(DECK_COL[i]);
            c.setTextSize(22);
            c.setPadding((int) (9 * d), (int) (2 * d), (int) (9 * d), (int) (2 * d));
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { toggleDeck(bit); }
            });
            chips[i] = c;
            row.addView(c);
        }
        refreshChips();
    }

    /** 덱 색 토글 (아무것도 안 고르면 전부 같은 가치). 바꾸면 마지막 보드를 다시 계산. */
    private void toggleDeck(int bit) {
        int m = prefs.deck();
        prefs.setDeck((m & bit) != 0 ? (m & ~bit) : (m | bit));
        refreshChips();
        replanAsync();
    }

    private void refreshChips() {
        int m = prefs.deck();
        for (int i = 0; i < 5; i++) {
            chips[i].setAlpha(m == 0 ? 0.7f : ((m & (1 << i)) != 0 ? 1f : 0.25f));
            chips[i].setText((m & (1 << i)) != 0 ? "◉" : "●");
        }
    }

    private void replanAsync() {
        if (lastBoard != null && !busy) {
            busy = true;
            work.post(new Runnable() {
                public void run() {
                    try { replan(); } catch (Throwable t) { toast("계산 오류: " + t); } finally { busy = false; }
                }
            });
        }
    }

    private void refreshN() { nLabel.setText(String.valueOf(prefs.manualN())); }

    private TextView mkBtn(String s) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
        return t;
    }

    private void refreshModeLabel() {
        btnMode.setText(prefs.mode() == Prefs.MODE_AUTO ? "자동" : "수동");
        btnAnalyze.setAlpha(prefs.mode() == Prefs.MODE_AUTO ? 0.5f : 1f);
    }

    /** 눈 버튼: 경로·설명·버튼을 접고 눈/이동 핸들만 남김 (다시 누르면 펼침) */
    private void setCollapsed(boolean c) {
        collapsed = c;
        for (int i = 0; i < row1Ref.getChildCount(); i++) {
            View ch = row1Ref.getChildAt(i);
            if (i > 1) ch.setVisibility(c ? View.GONE : View.VISIBLE);   // 0=핸들, 1=눈
        }
        row2Ref.setVisibility(c ? View.GONE : View.VISIBLE);
        btnEye.setAlpha(c ? 0.6f : 1f);
        if (pathView != null) pathView.setVisibility(c ? View.INVISIBLE : View.VISIBLE);
    }

    private void setOverlayVisible(boolean v) {
        int vis = v ? View.VISIBLE : View.INVISIBLE;
        if (pathView != null) pathView.setVisibility(collapsed ? View.INVISIBLE : vis);
        if (panel != null) panel.setVisibility(vis);
    }

    private void toast(final String s) {
        ui.post(new Runnable() { public void run() { Toast.makeText(OverlayService.this, s, Toast.LENGTH_SHORT).show(); } });
    }

    @Override
    public void onDestroy() {
        running = false;
        if (prefs != null && prefListener != null) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (ui != null) ui.removeCallbacksAndMessages(null);
        try { if (pathView != null) wm.removeView(pathView); } catch (Exception ignore) { }
        try { if (panel != null) wm.removeView(panel); } catch (Exception ignore) { }
        if (vdisplay != null) vdisplay.release();
        if (reader != null) reader.close();
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignore) { }
        }
        if (workThread != null) workThread.quitSafely();
        super.onDestroy();
    }
}
