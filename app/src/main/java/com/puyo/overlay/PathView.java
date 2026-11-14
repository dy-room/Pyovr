package com.puyo.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 게임 화면 위에 추천 경로를 그리는 투명 뷰 (터치 통과) */
final class PathView extends View {
    private Planner.Item item;
    private Reader.Geom geom;
    private String info = "";
    private char[][] grid;
    private char[] preview;
    private java.util.List<int[]> chance;
    private boolean showLabels;
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Prefs prefs;

    PathView(Context c, Prefs prefs) {
        super(c);
        this.prefs = prefs;
        float d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(7 * d);
        line.setColor(0xE6FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
        ring.setColor(0xFF111111);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xF0FF2D55);
        txt.setColor(0xFFFFFFFF);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setFakeBoldText(true);
        bg.setColor(0x70000000);
        infoPaint.setColor(0xE6FFFFFF);
        infoPaint.setTextSize(15 * d);
        infoPaint.setFakeBoldText(true);
    }

    void show(Reader.Geom g, Planner.Item it, String text) {
        geom = g;
        item = it;
        info = text == null ? "" : text;
        postInvalidate();
    }

    /** 마지막으로 인식한 보드 (인식값 표시용) */
    void setBoard(Reader.Geom g, char[][] gr, char[] pv, java.util.List<int[]> ch) {
        geom = g;
        grid = gr;
        preview = pv;
        chance = ch;
        postInvalidate();
    }

    void setShowLabels(boolean v) {
        showLabels = v;
        postInvalidate();
    }

    private static int letterColor(char c) {
        switch (c) {
            case 'R': return 0xFFFF5252;
            case 'B': return 0xFF64B5F6;
            case 'G': return 0xFF66BB6A;
            case 'Y': return 0xFFFFD740;
            case 'P': return 0xFFB388FF;
            case 'H': return 0xFFFF80AB;
            case 'X': return 0xFFFFFFFF;
            case 'O': return 0xFFBDBDBD;
            default: return 0xFFFFFFFF;
        }
    }

    private void drawLabels(Canvas cv) {
        if (!showLabels || grid == null || geom == null) return;
        float u = (float) geom.sx;
        cellPaint.setStyle(Paint.Style.STROKE);
        cellPaint.setStrokeWidth(Math.max(1f, 1.5f * u));
        cellPaint.setColor(0x66FFFFFF);
        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(0xB00A0C1C);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setFakeBoldText(true);
        letterPaint.setTextSize(22 * u);
        float cw = (float) geom.cw, chh = (float) geom.ch;
        for (int r = 0; r < Reader.ROWS; r++) {
            for (int c = 0; c < Reader.COLS; c++) {
                float cx = (float) geom.xs[c], cy = (float) geom.ys[r];
                cv.drawRect(cx - cw / 2, cy - chh / 2, cx + cw / 2, cy + chh / 2, cellPaint);
                float bx = cx - cw / 2 + 4 * u, by = cy - chh / 2 + 4 * u, bs = 30 * u;
                cv.drawRect(bx, by, bx + bs, by + bs, boxPaint);
                char v = grid[r][c];
                letterPaint.setColor(letterColor(v));
                String t = v == '.' ? "·" : String.valueOf(v);
                cv.drawText(t, bx + bs / 2, by + bs / 2 + 8 * u, letterPaint);
                if (chance != null) {
                    for (int[] p : chance) if (p[0] == r && p[1] == c) {
                        letterPaint.setColor(0xFFFFFFFF);
                        cv.drawText("✦", cx + cw / 2 - 20 * u, cy - chh / 2 + 28 * u, letterPaint);
                    }
                }
            }
        }
        if (preview != null) {
            for (int c = 0; c < Reader.COLS; c++) {
                float cx = (float) geom.xs[c], cy = (float) geom.yp;
                cv.drawCircle(cx, cy, 17 * u, cellPaint);
                letterPaint.setColor(letterColor(preview[c]));
                cv.drawText(String.valueOf(preview[c] == '.' ? '·' : preview[c]), cx, cy + 8 * u, letterPaint);
            }
        }
    }

    void clear() {
        item = null;
        info = "";
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        drawLabels(cv);
        if (!info.isEmpty()) {
            float pad = 8 * getResources().getDisplayMetrics().density;
            float tw = infoPaint.measureText(info);
            // 캐릭터 아이콘 줄(기준 y≈1155/2340) 바로 위
            float y = geom != null ? (float) (1128 * geom.sy) : 120;
            float x = 12 * getResources().getDisplayMetrics().density;
            cv.drawRect(x - pad, y - infoPaint.getTextSize() - pad * 0.5f, x + tw + pad, y + pad, bg);
            cv.drawText(info, x, y, infoPaint);
        }
        if (item == null || geom == null) return;
        float op = prefs.opacity() / 100f;
        line.setAlpha(Math.round(0xE6 * op));
        dot.setAlpha(Math.round(0xF0 * op));
        ring.setAlpha(Math.round(0xFF * op));
        txt.setAlpha(Math.round(0xFF * op));
        int[] p = item.path;
        Path path = new Path();
        float r = (float) (Math.min(geom.cw, geom.ch) * 0.30);
        for (int i = 0; i < p.length; i++) {
            float x = (float) geom.xs[p[i] % Reader.COLS], y = (float) geom.ys[p[i] / Reader.COLS];
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        cv.drawPath(path, line);
        txt.setTextSize(r * 1.2f);
        for (int i = 0; i < p.length; i++) {
            float x = (float) geom.xs[p[i] % Reader.COLS], y = (float) geom.ys[p[i] / Reader.COLS];
            cv.drawCircle(x, y, r, dot);
            cv.drawCircle(x, y, r, ring);
            cv.drawText(String.valueOf(i + 1), x, y + r * 0.42f, txt);
        }
    }
}
