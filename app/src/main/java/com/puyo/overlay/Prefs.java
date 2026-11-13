package com.puyo.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/** 앱 설정 (액티비티와 서비스가 공유) */
final class Prefs {
    static final int MODE_AUTO = 1, MODE_BUTTON = 2;
    final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    int mode() { return sp.getInt("mode", MODE_BUTTON); }
    void setMode(int m) { sp.edit().putInt("mode", m).apply(); }

    boolean autoN() { return sp.getBoolean("autoN", true); }
    void setAutoN(boolean v) { sp.edit().putBoolean("autoN", v).apply(); }

    int manualN() { return sp.getInt("manualN", 5); }
    void setManualN(int n) { sp.edit().putInt("manualN", Math.max(1, Math.min(10, n))).apply(); }

    /** 덱 색 비트마스크 (R,B,G,Y,P = bit0..4). 0 이면 전부 주력 */
    int deck() { return sp.getInt("deck", 0); }
    void setDeck(int m) { sp.edit().putInt("deck", m).apply(); }

    float mainW() { return 3f; }
    float otherW() { return 0.4f; }
}
