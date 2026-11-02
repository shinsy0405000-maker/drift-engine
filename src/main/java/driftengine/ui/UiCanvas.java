package driftengine.ui;

import java.util.Arrays;

/**
 * 한 프레임 동안 화면에 그릴 사각형·글자를 모으는 곳 (좌표 = 화면 픽셀, 왼쪽 위 0,0).
 * 정점 하나 = x, y, u, v, r, g, b, a (float 8개). 사각형 하나 = 삼각형 2개 = 정점 6개.
 */
public final class UiCanvas {

    public static final int FLOATS_PER_VERTEX = 8;

    public enum Align { LEFT, CENTER, RIGHT }

    private final FontAtlas atlas;
    private float[] data = new float[FLOATS_PER_VERTEX * 6 * 1024];
    private int count;
    private int width;
    private int height;

    public UiCanvas(FontAtlas atlas) {
        this.atlas = atlas;
    }

    /** 새 프레임 시작 */
    public void begin(int width, int height) {
        this.width = width;
        this.height = height;
        count = 0;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 화면 높이 720 기준 크기 → 지금 해상도 크기 (UI 가 해상도에 맞춰 커짐) */
    public float scale() {
        return height / 720f;
    }

    public FontAtlas atlas() {
        return atlas;
    }

    /** 색 사각형. rgba 는 0xRRGGBBAA */
    public void rect(float x, float y, float w, float h, int rgba) {
        float u = atlas.whiteU();
        float v = atlas.whiteV();
        quad(x, y, x + w, y + h, u, v, u, v, rgba);
    }

    /** 세로 그라데이션 사각형 (위 색 → 아래 색) */
    public void rectV(float x, float y, float w, float h, int top, int bottom) {
        float u = atlas.whiteU();
        float v = atlas.whiteV();
        quad4(x, y, x + w, y + h, u, v, u, v, top, top, bottom, bottom);
    }

    /** 가로 그라데이션 사각형 (왼쪽 색 → 오른쪽 색) */
    public void rectH(float x, float y, float w, float h, int left, int right) {
        float u = atlas.whiteU();
        float v = atlas.whiteV();
        quad4(x, y, x + w, y + h, u, v, u, v, left, right, right, left);
    }

    /** 둥근 사각형 (가장자리 부드럽게). r = 모서리 반지름 */
    public void roundRect(float x, float y, float w, float h, float r, int rgba) {
        roundRectV(x, y, w, h, r, rgba, rgba);
    }

    /** 둥근 사각형 + 세로 그라데이션 */
    public void roundRectV(float x, float y, float w, float h, float r, int top, int bottom) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        nineSlice(FontAtlas.DISC, x, y, w, h, r, top, bottom);
    }

    /** 둥근 테두리 (두께 t) */
    public void roundFrame(float x, float y, float w, float h, float r, float t, int rgba) {
        // 바깥 둥근 사각형에서 안쪽을 비울 수 없으므로 네 변 + 네 모서리 호로 그린다
        r = Math.max(t, Math.min(r, Math.min(w, h) / 2));
        rect(x + r, y, w - 2 * r, t, rgba);
        rect(x + r, y + h - t, w - 2 * r, t, rgba);
        rect(x, y + r, t, h - 2 * r, rgba);
        rect(x + w - t, y + r, t, h - 2 * r, rgba);
        arc(x + r, y + r, r - t, r, (float) Math.PI, (float) (Math.PI * 1.5), rgba);
        arc(x + w - r, y + r, r - t, r, (float) (Math.PI * 1.5), (float) (Math.PI * 2), rgba);
        arc(x + w - r, y + h - r, r - t, r, 0, (float) (Math.PI * 0.5), rgba);
        arc(x + r, y + h - r, r - t, r, (float) (Math.PI * 0.5), (float) Math.PI, rgba);
    }

    /** 부드러운 그림자 (사각형 둘레로 blur 만큼 번짐) */
    public void shadow(float x, float y, float w, float h, float blur, int rgba) {
        nineSlice(FontAtlas.BLOB, x - blur, y - blur, w + 2 * blur, h + 2 * blur, blur * 2, rgba, rgba);
    }

    /** 빛 번짐 원 (가운데가 밝음) */
    public void glow(float cx, float cy, float r, int rgba) {
        float u = atlas.shapeU(FontAtlas.BLOB);
        float v = atlas.shapeV();
        float d = FontAtlas.SHAPE / (float) FontAtlas.SIZE;
        quad4(cx - r, cy - r, cx + r, cy + r, u, v, u + d, v + d, rgba, rgba, rgba, rgba);
    }

    /** 꽉 찬 원 (가장자리 부드럽게) */
    public void circle(float cx, float cy, float r, int rgba) {
        float u = atlas.shapeU(FontAtlas.DISC);
        float v = atlas.shapeV();
        float d = FontAtlas.SHAPE / (float) FontAtlas.SIZE;
        quad4(cx - r, cy - r, cx + r, cy + r, u, v, u + d, v + d, rgba, rgba, rgba, rgba);
    }

    /**
     * 고리의 한 조각 (반지름 r0~r1, 각도 a0→a1, 라디안. 0 = 오른쪽, 시계 방향 = 화면 아래쪽으로).
     * 계기판 바늘 눈금·회전계 띠용.
     */
    public void arc(float cx, float cy, float r0, float r1, float a0, float a1, int rgba) {
        arc(cx, cy, r0, r1, a0, a1, rgba, rgba);
    }

    /** 고리 조각, 시작 색 → 끝 색 */
    public void arc(float cx, float cy, float r0, float r1, float a0, float a1, int from, int to) {
        float u = atlas.whiteU();
        float v = atlas.whiteV();
        int seg = Math.max(2, (int) Math.ceil(Math.abs(a1 - a0) / 0.06f));
        for (int i = 0; i < seg; i++) {
            float t0 = i / (float) seg;
            float t1 = (i + 1) / (float) seg;
            float b0 = a0 + (a1 - a0) * t0;
            float b1 = a0 + (a1 - a0) * t1;
            int c0 = lerpColor(from, to, t0);
            int c1 = lerpColor(from, to, t1);
            float c0x = (float) Math.cos(b0), c0y = (float) Math.sin(b0), c1x = (float) Math.cos(b1), c1y = (float) Math.sin(b1);
            tri(cx + c0x * r0, cy + c0y * r0, cx + c0x * r1, cy + c0y * r1, cx + c1x * r1, cy + c1y * r1, u, v, c0, c0, c1);
            tri(cx + c0x * r0, cy + c0y * r0, cx + c1x * r1, cy + c1y * r1, cx + c1x * r0, cy + c1y * r0, u, v, c0, c1, c1);
        }
    }

    /** 굵은 선 (바늘 등) */
    public void line(float x0, float y0, float x1, float y1, float width, int rgba) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) {
            return;
        }
        float nx = -dy / len * width / 2, ny = dx / len * width / 2;
        float u = atlas.whiteU();
        float v = atlas.whiteV();
        tri(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, u, v, rgba, rgba, rgba);
        tri(x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, u, v, rgba, rgba, rgba);
    }

    /** 평행사변형 (기울어진 띠, 레이싱 게임 느낌의 제목 배경). skew = 위쪽이 오른쪽으로 밀리는 양 (px) */
    public void slant(float x, float y, float w, float h, float skew, int left, int right) {
        float u = atlas.whiteU();
        float v = atlas.whiteV();
        tri(x + skew, y, x + w + skew, y, x + w, y + h, u, v, left, right, right);
        tri(x + skew, y, x + w, y + h, x, y + h, u, v, left, right, left);
    }

    public static int lerpColor(int a, int b, float t) {
        int out = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = (a >>> sh) & 0xFF;
            int cb = (b >>> sh) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
        }
        return out;
    }

    /** 색의 투명도만 바꾼 값 (alpha 0~1 을 곱함) */
    public static int alpha(int rgba, float a) {
        int al = Math.round((rgba & 0xFF) * Math.max(0, Math.min(1, a)));
        return (rgba & 0xFFFFFF00) | al;
    }

    /** 도형 칸(shape)을 9조각으로 늘여 그린다: 모서리는 원의 1/4, 변과 가운데는 늘인 띠 */
    private void nineSlice(int shape, float x, float y, float w, float h, float r, int top, int bottom) {
        float u0 = atlas.shapeU(shape);
        float v0 = atlas.shapeV();
        float d = FontAtlas.SHAPE / (float) FontAtlas.SIZE;
        float um = u0 + d / 2, vm = v0 + d / 2, u1 = u0 + d, v1 = v0 + d;
        float[] xs = {x, x + r, x + w - r, x + w};
        float[] ys = {y, y + r, y + h - r, y + h};
        float[] us = {u0, um, um, u1};
        float[] vs = {v0, vm, vm, v1};
        for (int j = 0; j < 3; j++) {
            if (ys[j + 1] - ys[j] <= 0) {
                continue;
            }
            float tTop = h <= 0 ? 0 : (ys[j] - y) / h;
            float tBot = h <= 0 ? 0 : (ys[j + 1] - y) / h;
            int ct = lerpColor(top, bottom, tTop);
            int cb = lerpColor(top, bottom, tBot);
            for (int i = 0; i < 3; i++) {
                if (xs[i + 1] - xs[i] <= 0) {
                    continue;
                }
                quad4(xs[i], ys[j], xs[i + 1], ys[j + 1], us[i], vs[j], us[i + 1], vs[j + 1], ct, ct, cb, cb);
            }
        }
    }

    /** 테두리만 */
    public void frame(float x, float y, float w, float h, float t, int rgba) {
        rect(x, y, w, t, rgba);
        rect(x, y + h - t, w, t, rgba);
        rect(x, y + t, t, h - 2 * t, rgba);
        rect(x + w - t, y + t, t, h - 2 * t, rgba);
    }

    /** 글자 폭 (px) */
    public float textWidth(String text, float px) {
        float s = px / FontAtlas.RASTER_PX;
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            FontAtlas.Glyph gl = atlas.glyph(cp);
            if (gl != null) {
                w += gl.advance() * s;
            }
        }
        return w;
    }

    /**
     * 글자. (x, y) = 글자 줄의 위쪽. px = 글자 높이(대략).
     * @return 그린 폭
     */
    public float text(float x, float y, float px, String text, int rgba, Align align) {
        float s = px / FontAtlas.RASTER_PX;
        float w = textWidth(text, px);
        float pen = switch (align) {
            case LEFT -> x;
            case CENTER -> x - w / 2;
            case RIGHT -> x - w;
        };
        float baseline = y + atlas.ascent() * s;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            FontAtlas.Glyph gl = atlas.glyph(cp);
            if (gl == null) {
                continue;
            }
            if (gl.width() > 0) {
                float gx = pen + gl.offsetX() * s;
                float gy = baseline + gl.offsetY() * s;
                quad(gx, gy, gx + gl.width() * s, gy + gl.height() * s, gl.u0(), gl.v0(), gl.u1(), gl.v1(), rgba);
            }
            pen += gl.advance() * s;
        }
        return w;
    }

    public float text(float x, float y, float px, String text, int rgba) {
        return text(x, y, px, text, rgba, Align.LEFT);
    }

    /** 그림자 있는 글자 (밝은 배경에서도 잘 보이게) */
    public float shadowText(float x, float y, float px, String text, int rgba, Align align) {
        float o = Math.max(1, px / 18);
        text(x + o, y + o, px, text, Math.min(0xAA, rgba & 0xFF), align); // 검정, 투명도는 글자를 따름
        return text(x, y, px, text, rgba, align);
    }

    /** 네 꼭짓점 색이 다른 사각형: c00 왼위, c10 오른위, c11 오른아래, c01 왼아래 */
    private void quad4(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                       int c00, int c10, int c11, int c01) {
        ensure(6);
        putC(x0, y0, u0, v0, c00);
        putC(x1, y0, u1, v0, c10);
        putC(x1, y1, u1, v1, c11);
        putC(x0, y0, u0, v0, c00);
        putC(x1, y1, u1, v1, c11);
        putC(x0, y1, u0, v1, c01);
    }

    private void tri(float x0, float y0, float x1, float y1, float x2, float y2, float u, float v, int c0, int c1, int c2) {
        ensure(3);
        putC(x0, y0, u, v, c0);
        putC(x1, y1, u, v, c1);
        putC(x2, y2, u, v, c2);
    }

    private void putC(float x, float y, float u, float v, int rgba) {
        put(x, y, u, v, ((rgba >>> 24) & 0xFF) / 255f, ((rgba >>> 16) & 0xFF) / 255f, ((rgba >>> 8) & 0xFF) / 255f,
                (rgba & 0xFF) / 255f);
    }

    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int rgba) {
        float r = ((rgba >>> 24) & 0xFF) / 255f;
        float g = ((rgba >>> 16) & 0xFF) / 255f;
        float b = ((rgba >>> 8) & 0xFF) / 255f;
        float a = (rgba & 0xFF) / 255f;
        ensure(6);
        put(x0, y0, u0, v0, r, g, b, a);
        put(x1, y0, u1, v0, r, g, b, a);
        put(x1, y1, u1, v1, r, g, b, a);
        put(x0, y0, u0, v0, r, g, b, a);
        put(x1, y1, u1, v1, r, g, b, a);
        put(x0, y1, u0, v1, r, g, b, a);
    }

    private void ensure(int vertices) {
        int need = (count + vertices) * FLOATS_PER_VERTEX;
        if (need > data.length) {
            data = Arrays.copyOf(data, Math.max(need, data.length * 2));
        }
    }

    private void put(float x, float y, float u, float v, float r, float g, float b, float a) {
        int o = count * FLOATS_PER_VERTEX;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = u;
        data[o + 3] = v;
        data[o + 4] = r;
        data[o + 5] = g;
        data[o + 6] = b;
        data[o + 7] = a;
        count++;
    }

    public int vertexCount() {
        return count;
    }

    public float[] data() {
        return data;
    }
}
