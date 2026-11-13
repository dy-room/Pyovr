package com.puyo.overlay;

/** 화면 한 장의 픽셀 접근(복사 없이). rgb 는 0xRRGGBB. */
public interface PixelSource {
    int width();

    int height();

    int rgb(int x, int y);
}
