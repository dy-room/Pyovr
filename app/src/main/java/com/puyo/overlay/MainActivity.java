package com.puyo.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1, REQ_CAPTURE = 2, REQ_NOTI = 3;
    private Prefs prefs;
    private TextView status, nVal;
    private Button startBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        float d = getResources().getDisplayMetrics().density;
        int p = (int) (18 * d);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        root.addView(label("갱신 방식"));
        RadioGroup rg = new RadioGroup(this);
        RadioButton rbBtn = new RadioButton(this), rbAuto = new RadioButton(this);
        rbBtn.setText("버튼을 누를 때만 분석 (기본)");
        rbAuto.setText("자동으로 계속 분석 (약 1초마다, 화면이 깜빡일 수 있음)");
        rbBtn.setId(View.generateViewId());
        rbAuto.setId(View.generateViewId());
        rg.addView(rbBtn);
        rg.addView(rbAuto);
        (prefs.mode() == Prefs.MODE_AUTO ? rbAuto : rbBtn).setChecked(true);
        final int idAuto = rbAuto.getId();
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) { prefs.setMode(id == idAuto ? Prefs.MODE_AUTO : Prefs.MODE_BUTTON); }
        });
        root.addView(rg);

        root.addView(label("훑을 칸 수"));
        CheckBox auto = new CheckBox(this);
        auto.setText("화면의 のこり N 자동 인식 (실패하면 아래 값 사용)");
        auto.setChecked(prefs.autoN());
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean c) { prefs.setAutoN(c); }
        });
        root.addView(auto);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = new Button(this), plus = new Button(this);
        minus.setText("−");
        plus.setText("＋");
        nVal = new TextView(this);
        nVal.setTextSize(20);
        nVal.setGravity(Gravity.CENTER);
        nVal.setMinWidth((int) (60 * d));
        minus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { prefs.setManualN(prefs.manualN() - 1); refreshN(); } });
        plus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { prefs.setManualN(prefs.manualN() + 1); refreshN(); } });
        row.addView(minus);
        row.addView(nVal);
        row.addView(plus);
        root.addView(row);
        refreshN();

        root.addView(label("계산 옵션"));
        CheckBox pre = new CheckBox(this);
        pre.setText("확정 연쇄 수 우선 (연쇄를 더 길게 만드는 수를 먼저)");
        pre.setChecked(prefs.preFirst());
        pre.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean c) { prefs.setPreFirst(c); }
        });
        root.addView(pre);
        CheckBox tr = new CheckBox(this);
        tr.setText("훑은 뿌요도 점수에 포함 (실험용, 게임 규칙과 다르면 끄기)");
        tr.setChecked(prefs.traceScore());
        tr.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean c) { prefs.setTraceScore(c); }
        });
        root.addView(tr);

        root.addView(label("경로 표시 투명도"));
        final TextView opVal = new TextView(this);
        opVal.setText(prefs.opacity() + "%  (낮을수록 투명)");
        SeekBar sb = new SeekBar(this);
        sb.setMax(80);
        sb.setProgress(prefs.opacity() - 20);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int pr, boolean user) {
                prefs.setOpacity(pr + 20);
                opVal.setText((pr + 20) + "%  (낮을수록 투명)");
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        root.addView(opVal);
        root.addView(sb);

        root.addView(label("덱 색 (한 가지만 선택, 안 고르면 전부 같은 가치)"));
        String[] names = {"빨강", "파랑", "초록", "노랑", "보라"};
        int[] cols = {0xFFD32F2F, 0xFF1976D2, 0xFF388E3C, 0xFFF9A825, 0xFF7B1FA2};
        LinearLayout deck = new LinearLayout(this);
        final CheckBox[] cbs = new CheckBox[5];
        for (int i = 0; i < 5; i++) {
            final int bit = 1 << i;
            final int idx = i;
            CheckBox cb = new CheckBox(this);
            cbs[i] = cb;
            cb.setText(names[i]);
            cb.setTextColor(cols[i]);
            cb.setChecked((prefs.deck() & bit) != 0);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton v, boolean c) {
                    if (c) {   // 한 가지만 선택
                        prefs.setDeck(bit);
                        for (int k = 0; k < 5; k++) if (k != idx && cbs[k] != null && cbs[k].isChecked()) cbs[k].setChecked(false);
                    } else if (prefs.deck() == bit) {
                        prefs.setDeck(0);
                    }
                }
            });
            deck.addView(cb);
        }
        root.addView(deck);

        startBtn = new Button(this);
        startBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onStartStop(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (20 * d);
        root.addView(startBtn, lp);

        status = new TextView(this);
        status.setPadding(0, (int) (12 * d), 0, 0);
        status.setTextColor(Color.DKGRAY);
        root.addView(status);
        setContentView(sv);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 4);
        return t;
    }

    private void refreshN() { nVal.setText(String.valueOf(prefs.manualN())); }

    @Override
    protected void onResume() {
        super.onResume();
        refreshButton();
    }

    private void refreshButton() {
        startBtn.setText(OverlayService.running ? "오버레이 중지" : "오버레이 시작");
        status.setText(OverlayService.running
                ? "실행 중입니다. 뿌요 퀘스트로 이동해서 화면 위 [분석] 버튼을 누르세요."
                : "시작하면 화면 캡처 권한 창이 뜹니다. 허용 후 게임으로 이동하세요.");
    }

    private void onStartStop() {
        if (OverlayService.running) {
            Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP);
            startService(i);
            startBtn.postDelayed(new Runnable() { public void run() { refreshButton(); } }, 300);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            status.setText("'다른 앱 위에 표시' 권한을 허용한 뒤 다시 눌러주세요.");
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_NOTI) onStartStop();   // 거부해도 진행 (알림 없이도 서비스는 동작)
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_CAPTURE) {
            if (res == RESULT_OK && data != null) {
                Intent i = new Intent(this, OverlayService.class);
                i.putExtra(OverlayService.EXTRA_CODE, res);
                i.putExtra(OverlayService.EXTRA_DATA, data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
                status.setText("시작했어요. 뿌요 퀘스트로 이동하세요.");
                startBtn.postDelayed(new Runnable() { public void run() { refreshButton(); } }, 600);
            } else {
                status.setText("화면 캡처 권한이 거부되었어요.");
            }
        } else if (req == REQ_OVERLAY) {
            refreshButton();
        }
    }
}
