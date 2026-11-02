package driftengine.ui;

import driftengine.core.Log;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 글자 그림 모음(아틀라스). 처음 쓰는 글자가 나오면 그때 그려서 넣는다(한글 11,172자를 미리 다 그리지 않음).
 * <p>
 * Java 기본 글꼴 그리기(java.awt, 화면 없이 동작)를 써서 외부 라이브러리가 필요 없다.
 * 한글 글꼴: Windows 맑은 고딕 → Noto Sans CJK KR → 기본 SansSerif 순서로 찾는다.
 * 아틀라스는 한 장(SIZE × SIZE, 8비트 알파). 바뀌면 dirty 가 되어 렌더러가 GPU 로 다시 올린다.
 * (0,0) 4×4 칸은 흰색: 글자 아닌 사각형도 같은 셰이더로 그리기 위해.
 */
public final class FontAtlas {

    public static final int SIZE = 2048;
    /** 아틀라스에 그리는 글자 크기 (px). 화면 글자는 이것을 늘이거나 줄여서 그린다 */
    public static final int RASTER_PX = 40;
    private static final int PAD = 2;
    /** 아틀라스 맨 아래 줄: 도형 그림 (UiCanvas 의 둥근 사각형·원·그림자용). 글자는 이 위까지만 */
    public static final int SHAPE = 128;
    private static final int SHAPE_Y = SIZE - SHAPE;
    /** 도형 칸 번호: 0 = 매끈한 원(가장자리 부드럽게), 1 = 흐린 원(그림자·빛) */
    public static final int DISC = 0;
    public static final int BLOB = 1;

    /** 글자 하나: 아틀라스 위치(u0..v1, 0~1), 크기(px, RASTER_PX 기준), 기준선에서 위치 */
    public record Glyph(float u0, float v0, float u1, float v1, int width, int height, int offsetX, int offsetY,
                        float advance) {
    }

    private final byte[] pixels = new byte[SIZE * SIZE];
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Font font;
    private final BufferedImage scratch = new BufferedImage(RASTER_PX * 2, RASTER_PX * 2, BufferedImage.TYPE_BYTE_GRAY);
    private final Graphics2D g;
    private final FontMetrics metrics;
    private int penX = 8;
    private int penY = 0;
    private int rowHeight = 0;
    private boolean dirty = true;
    private boolean full;

    public FontAtlas() {
        System.setProperty("java.awt.headless", "true");
        font = chooseFont();
        g = scratch.createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        metrics = g.getFontMetrics();
        // 흰 칸 (사각형용)
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                pixels[y * SIZE + x] = (byte) 255;
            }
        }
        drawShapes();
        Log.info("글꼴: " + font.getFontName() + " (" + RASTER_PX + "px 로 그려 아틀라스 " + SIZE + "x" + SIZE + ")");
    }

    private static Font chooseFont() {
        String[] names = {"Malgun Gothic", "맑은 고딕", "Noto Sans CJK KR", "Noto Sans KR", "NanumGothic", "Apple SD Gothic Neo"};
        java.util.Set<String> available = new java.util.HashSet<>(
                java.util.Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : names) {
            if (available.contains(n)) {
                return new Font(n, Font.BOLD, RASTER_PX);
            }
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, RASTER_PX);
    }

    /** 도형 그림: 원(4x4 표본으로 가장자리 부드럽게) + 흐린 원(가우스) */
    private void drawShapes() {
        float c = SHAPE / 2f;
        for (int y = 0; y < SHAPE; y++) {
            for (int x = 0; x < SHAPE; x++) {
                int inside = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        float dx = x + (sx + 0.5f) / 4 - c;
                        float dy = y + (sy + 0.5f) / 4 - c;
                        if (dx * dx + dy * dy <= (c - 1) * (c - 1)) {
                            inside++;
                        }
                    }
                }
                pixels[(SHAPE_Y + y) * SIZE + x] = (byte) Math.round(inside * 255 / 16f);
                float dx = x + 0.5f - c;
                float dy = y + 0.5f - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy) / c; // 0 가운데 ~ 1 가장자리
                float blob = d >= 1 ? 0 : (float) Math.exp(-d * d * 4.5f) * (1 - d * d * d * d);
                pixels[(SHAPE_Y + y) * SIZE + SHAPE + x] = (byte) Math.round(Math.min(1, blob * 1.02f) * 255);
            }
        }
    }

    /** 도형 칸의 왼쪽 위 UV */
    public float shapeU(int shape) {
        return shape * SHAPE / (float) SIZE;
    }

    public float shapeV() {
        return SHAPE_Y / (float) SIZE;
    }

    /** 흰 칸의 가운데 UV (사각형 그리기용) */
    public float whiteU() {
        return 2f / SIZE;
    }

    public float whiteV() {
        return 2f / SIZE;
    }

    /** 기준선에서 위쪽 높이 (RASTER_PX 기준) */
    public int ascent() {
        return metrics.getAscent();
    }

    public int lineHeight() {
        return metrics.getHeight();
    }

    /** 글자 정보 (없으면 지금 그려서 넣는다). 아틀라스가 꽉 차면 null */
    public Glyph glyph(int codePoint) {
        Glyph existing = glyphs.get(codePoint);
        if (existing != null || full) {
            return existing;
        }
        return add(codePoint);
    }

    private Glyph add(int cp) {
        String s = new String(Character.toChars(cp));
        float advance = (float) font.getStringBounds(s, g.getFontRenderContext()).getWidth();
        java.awt.Rectangle b = font.createGlyphVector(g.getFontRenderContext(), s).getPixelBounds(null, 0, 0);
        int w = Math.max(1, b.width + 2);
        int h = Math.max(1, b.height + 2);
        if (Character.isWhitespace(cp) || b.isEmpty()) {
            Glyph empty = new Glyph(0, 0, 0, 0, 0, 0, 0, 0, advance);
            glyphs.put(cp, empty);
            return empty;
        }
        if (w > scratch.getWidth() || h > scratch.getHeight()) {
            return null;
        }
        if (penX + w + PAD > SIZE) {
            penX = 0;
            penY += rowHeight + PAD;
            rowHeight = 0;
        }
        if (penY + h + PAD > SHAPE_Y - 2) {
            full = true;
            Log.warn("글자 아틀라스가 꽉 찼습니다 (" + glyphs.size() + "자)");
            return null;
        }
        // 글자를 scratch 에 그린 뒤 아틀라스로 복사
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, scratch.getWidth(), scratch.getHeight());
        g.setColor(java.awt.Color.WHITE);
        int ox = -b.x + 1;
        int oy = -b.y + 1;
        g.drawString(s, ox, oy);
        java.awt.image.Raster r = scratch.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[(penY + y) * SIZE + penX + x] = (byte) r.getSample(x, y, 0);
            }
        }
        Glyph glyph = new Glyph(penX / (float) SIZE, penY / (float) SIZE, (penX + w) / (float) SIZE, (penY + h) / (float) SIZE,
                w, h, b.x - 1, b.y - 1, advance);
        glyphs.put(cp, glyph);
        penX += w + PAD;
        rowHeight = Math.max(rowHeight, h);
        dirty = true;
        return glyph;
    }

    public byte[] pixels() {
        return pixels;
    }

    public boolean dirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public int glyphCount() {
        return glyphs.size();
    }
}
