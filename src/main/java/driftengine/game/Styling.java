package driftengine.game;

import driftengine.vehicle.visual.Livery;

import java.util.Objects;

/** 스타일링 샵 가격. 바뀐 항목마다 값을 더하고, 순정으로 되돌리는 항목은 무료. */
public final class Styling {

    public static final int PAINT = 1500;
    /** 마감: 솔리드, 메탈릭, 무광, 크롬 */
    public static final int[] FINISH = {800, 1500, 3000, 12000};
    public static final int RIM_DESIGN = 4000;
    public static final int RIM_COLOR = 800;
    public static final int CALIPER = 600;
    public static final int[] TINT = {0, 400, 600, 800};

    private Styling() {
    }

    public static int price(Livery from, Livery to) {
        int p = 0;
        if (to.paint() != from.paint() && to.paint() >= 0) {
            p += PAINT;
        }
        if (to.finish() != from.finish() && to.finish() >= 0) {
            p += FINISH[to.finish()];
        }
        if (!Objects.equals(to.rimFrom(), from.rimFrom()) && to.rimFrom() != null) {
            p += RIM_DESIGN;
        }
        if (to.rimColor() != from.rimColor() && to.rimColor() >= 0) {
            p += RIM_COLOR;
        }
        if (to.caliper() != from.caliper() && to.caliper() >= 0) {
            p += CALIPER;
        }
        if (to.tint() != from.tint()) {
            p += TINT[to.tint()];
        }
        return p;
    }
}
