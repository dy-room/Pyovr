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
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    PathView(Context c) {
        super(c);
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
        bg.setColor(0xCC000000);
        infoPaint.setColor(0xFFFFFFFF);
        infoPaint.setTextSize(15 * d);
        infoPaint.setFakeBoldText(true);
    }

    void show(Reader.Geom g, Planner.Item it, String text) {
        geom = g;
        item = it;
        info = text == null ? "" : text;
        postInvalidate();
    }

    void clear() {
        item = null;
        info = "";
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (!info.isEmpty()) {
            float pad = 8 * getResources().getDisplayMetrics().density;
            float tw = infoPaint.measureText(info);
            float y = geom != null ? (float) (geom.yp - geom.ch * 0.9) : 120;
            float x = 12 * getResources().getDisplayMetrics().density;
            cv.drawRect(x - pad, y - infoPaint.getTextSize() - pad * 0.5f, x + tw + pad, y + pad, bg);
            cv.drawText(info, x, y, infoPaint);
        }
        if (item == null || geom == null) return;
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
