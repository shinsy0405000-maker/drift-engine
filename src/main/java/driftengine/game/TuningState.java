package driftengine.game;

import driftengine.vehicle.visual.BodyKit;

import java.util.Arrays;

/** 차 한 대의 튜닝 상태: 부품별 단계 + 바디킷 + 직접 맞춘 값(캠버·오프셋) */
public record TuningState(int[] levels, BodyKit kit, Setup setup) {

    public static TuningState stock(BodyKit kit) {
        return new TuningState(new int[Part.values().length], kit, Setup.DEFAULT);
    }

    public TuningState(int[] levels, BodyKit kit) {
        this(levels, kit, Setup.DEFAULT);
    }

    public TuningState {
        if (levels.length != Part.values().length) {
            throw new IllegalArgumentException("부품 단계 개수 오류: " + levels.length);
        }
        for (Part p : Part.values()) {
            int l = levels[p.ordinal()];
            if (l < 0 || l > p.maxLevel()) {
                throw new IllegalArgumentException(p.label() + " 단계 범위 0~" + p.maxLevel() + ": " + l);
            }
        }
        levels = levels.clone();
        if (setup == null) {
            setup = Setup.DEFAULT;
        }
    }

    public int level(Part p) {
        return levels[p.ordinal()];
    }

    public TuningState with(Part p, int level) {
        int[] l = levels.clone();
        l[p.ordinal()] = level;
        return new TuningState(l, kit, setup);
    }

    public TuningState withKit(BodyKit k) {
        return new TuningState(levels, k, setup);
    }

    public TuningState withSetup(Setup s) {
        return new TuningState(levels, kit, s);
    }

    /** "1,0,2,0,0,0,0,0,0" (Part 순서) */
    public String levelsText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            sb.append(i == 0 ? "" : ",").append(levels[i]);
        }
        return sb.toString();
    }

    public static int[] parseLevels(String text) {
        String[] p = text.trim().split(",");
        int[] l = new int[Part.values().length];
        for (int i = 0; i < Math.min(p.length, l.length); i++) {
            l[i] = Integer.parseInt(p[i].trim());
        }
        return l;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TuningState t && Arrays.equals(levels, t.levels) && kit.equals(t.kit) && setup.equals(t.setup);
    }

    @Override
    public int hashCode() {
        return (Arrays.hashCode(levels) * 31 + kit.hashCode()) * 31 + setup.hashCode();
    }

    @Override
    public String toString() {
        return "TuningState[" + levelsText() + ", kit " + kit.text() + ", setup " + setup.text() + "]";
    }
}
