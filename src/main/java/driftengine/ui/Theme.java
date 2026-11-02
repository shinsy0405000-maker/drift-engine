package driftengine.ui;

import driftengine.ui.UiCanvas.Align;

/**
 * UI 색과 자주 쓰는 모양 (패널, 알약, 키 표시, 막대, 단계 점, 기울어진 제목).
 * 색은 0xRRGGBBAA (sRGB). 크기는 화면 높이 720 기준 px × s(UiCanvas.scale).
 */
public final class Theme {

    public static final int TEXT = 0xF4F6FAFF;
    public static final int DIM = 0x9AA6B8FF;
    public static final int FAINT = 0xFFFFFF22;
    public static final int LINE = 0xFFFFFF14;
    public static final int ACCENT = 0xFFB321FF;
    public static final int ACCENT_DEEP = 0xF07A12FF;
    public static final int CYAN = 0x38D6FFFF;
    public static final int GOOD = 0x52E08AFF;
    public static final int BAD = 0xFF4D5EFF;
    public static final int PANEL_TOP = 0x182131EB;
    public static final int PANEL_BOTTOM = 0x0A0E15EB;
    public static final int SHADOW = 0x00000080;
    public static final int SELECT_TOP = 0xFFB321F0;
    public static final int SELECT_BOTTOM = 0xF08A12F0;
    public static final int ROW = 0xFFFFFF0D;
    public static final int ROW_HOVER = 0xFFFFFF1C;

    private Theme() {
    }

    /** 클래스 배지 색 (1 회색, 2 초록, 3 파랑, 4 보라, CS 주황, CS+ 빨강) */
    public static int classColor(String label) {
        return switch (label) {
            case "1" -> 0x8A94A6FF;
            case "2" -> 0x3DBE6EFF;
            case "3" -> 0x2F8CF0FF;
            case "4" -> 0x9C5CF2FF;
            case "CS" -> 0xF58A1FFF;
            default -> 0xF0384AFF;
        };
    }

    /** 그림자 + 그라데이션 둥근 패널 + 위쪽 얇은 빛 */
    public static void panel(UiCanvas c, float x, float y, float w, float h, float r) {
        float s = c.scale();
        c.shadow(x, y + 4 * s, w, h, 18 * s, SHADOW);
        c.roundRectV(x, y, w, h, r, PANEL_TOP, PANEL_BOTTOM);
        c.rect(x + r, y, w - 2 * r, Math.max(1, s), 0xFFFFFF26);
    }

    /** 고른 줄 / 보통 줄 배경 */
    public static void row(UiCanvas c, float x, float y, float w, float h, float r, boolean selected) {
        if (selected) {
            c.glow(x + w / 2, y + h / 2, w * 0.55f, 0xFFB32130);
            c.roundRectV(x, y, w, h, r, SELECT_TOP, SELECT_BOTTOM);
        } else {
            c.roundRect(x, y, w, h, r, ROW);
        }
    }

    /** 둥근 알약 + 글자. @return 폭 */
    public static float pill(UiCanvas c, float x, float y, float px, String text, int bg, int fg, Align align) {
        float s = c.scale();
        float padX = px * 0.6f;
        float w = c.textWidth(text, px) + padX * 2;
        float h = px * 1.55f;
        float left = switch (align) {
            case LEFT -> x;
            case CENTER -> x - w / 2;
            case RIGHT -> x - w;
        };
        c.roundRect(left, y, w, h, h / 2, bg);
        c.text(left + padX, y + (h - px) / 2 - px * 0.08f, px, text, fg, Align.LEFT);
        return w + 0 * s;
    }

    /** 키 모양 상자 + 설명 ("Enter 구입"). @return 다음 x */
    public static float key(UiCanvas c, float x, float y, String key, String label) {
        float s = c.scale();
        float px = 13 * s;
        float kw = Math.max(26 * s, c.textWidth(key, px) + 14 * s);
        float kh = 24 * s;
        c.roundRect(x, y, kw, kh, 5 * s, 0xFFFFFFE6);
        c.text(x + kw / 2, y + 4 * s, px, key, 0x10141CFF, Align.CENTER);
        float tw = c.text(x + kw + 7 * s, y + 3 * s, 15 * s, label, TEXT, Align.LEFT);
        return x + kw + 7 * s + tw + 20 * s;
    }

    /** 클래스 배지 (정사각에 가까운 둥근 상자) */
    public static void classBadge(UiCanvas c, float x, float y, float h, String label) {
        float w = Math.max(h, c.textWidth(label, h * 0.62f) + h * 0.5f);
        int col = classColor(label);
        c.glow(x + w / 2, y + h / 2, h * 1.1f, UiCanvas.alpha(col, 0.35f));
        c.roundRectV(x, y, w, h, h * 0.22f, UiCanvas.lerpColor(col, 0xFFFFFFFF, 0.25f), col);
        c.text(x + w / 2, y + h * 0.16f, h * 0.62f, label, 0xFFFFFFFF, Align.CENTER);
    }

    /**
     * 가로 막대: 지금 값(흰색)과 바뀐 뒤 값(늘면 초록, 줄면 빨강). 값은 0~1.
     */
    public static void bar(UiCanvas c, float x, float y, float w, float h, float now, float after, int color) {
        now = clamp01(now);
        after = clamp01(after);
        c.roundRect(x, y, w, h, h / 2, 0xFFFFFF1A);
        if (after > now + 1e-3f) {
            c.roundRect(x, y, Math.max(h, w * after), h, h / 2, GOOD);
        }
        c.roundRect(x, y, Math.max(h, w * Math.min(now, after)), h, h / 2, color);
        if (after < now - 1e-3f) {
            c.roundRect(x + w * after, y, w * (now - after), h, h / 2, BAD);
        }
    }

    /** 단계 점 (●●○) — 칸 모양 */
    public static void pips(UiCanvas c, float x, float y, int level, int max, float size, int on) {
        float gap = size * 0.35f;
        for (int i = 0; i < max; i++) {
            float px = x + i * (size * 1.6f + gap);
            c.roundRect(px, y, size * 1.6f, size, size * 0.3f, i < level ? on : 0xFFFFFF26);
        }
    }

    /** 기울어진 제목 띠 + 큰 글자 (샵 제목) */
    public static void slantTitle(UiCanvas c, float x, float y, String big, String small, int color) {
        float s = c.scale();
        float bw = c.textWidth(big, 34 * s) + 46 * s;
        c.shadow(x, y, bw, 50 * s, 14 * s, 0x00000070);
        c.slant(x, y, bw, 50 * s, 14 * s, color, UiCanvas.lerpColor(color, 0x000000FF, 0.25f));
        c.text(x + 24 * s, y + 6 * s, 34 * s, big, 0x0B0F16FF, Align.LEFT);
        c.shadowText(x + bw + 22 * s, y + 14 * s, 20 * s, small, TEXT, Align.LEFT);
    }

    public static String money(long cr) {
        return String.format("%,d CR", cr);
    }

    static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
