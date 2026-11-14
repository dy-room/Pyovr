package com.puyo.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/** 앱 설정 (액티비티와 서비스가 공유) */
final class Prefs {
    static final int MODE_AUTO = 1, MODE_BUTTON = 2, MAX_N = 15;
    final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    int mode() { return sp.getInt("mode", MODE_BUTTON); }
    void setMode(int m) { sp.edit().putInt("mode", m).apply(); }

    boolean autoN() { return sp.getBoolean("autoN", false); }
    void setAutoN(boolean v) { sp.edit().putBoolean("autoN", v).apply(); }

    int manualN() { return sp.getInt("manualN", 5); }
    void setManualN(int n) { sp.edit().putInt("manualN", Math.max(1, Math.min(MAX_N, n))).apply(); }

    /** 덱 색 비트마스크 (R,B,G,Y,P = bit0..4). 0 이면 전부 주력 */
    int deck() { return sp.getInt("deck", 0); }
    void setDeck(int m) { sp.edit().putInt("deck", m).apply(); }

    /** 경로 오버레이 불투명도 20~100 (%) */
    int opacity() { return sp.getInt("opacity", 65); }
    void setOpacity(int v) { sp.edit().putInt("opacity", Math.max(20, Math.min(100, v))).apply(); }

    /** 확정 연쇄 수 우선 */
    boolean preFirst() { return sp.getBoolean("preFirst", false); }
    void setPreFirst(boolean v) { sp.edit().putBoolean("preFirst", v).apply(); }

    /** 훑은 뿌요 자체도 점수에 포함 (같은 색을 훑으면 그 색 가치만큼 점수) */
    boolean traceScore() { return sp.getBoolean("traceScore", true); }
    void setTraceScore(boolean v) { sp.edit().putBoolean("traceScore", v).apply(); }

    float mainW() { return 3f; }
    float otherW() { return 0.4f; }
}
