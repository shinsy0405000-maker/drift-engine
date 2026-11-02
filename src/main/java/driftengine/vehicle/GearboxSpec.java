package driftengine.vehicle;

import java.util.Arrays;

/**
 * 변속기 제원.
 *
 * @param ratios     전진 기어비 (1단부터). 전기차는 1개
 * @param reverse    후진 기어비 (양수로 적는다)
 * @param finalDrive 종감속비
 * @param shiftTime  변속하는 동안 힘이 끊기는 시간 (초). 수동 0.2, 듀얼클러치 0.08
 * @param efficiency 동력 전달 효율 (0.85~0.95)
 */
public record GearboxSpec(float[] ratios, float reverse, float finalDrive, float shiftTime, float efficiency) {

    public GearboxSpec {
        if (ratios.length == 0 || !(reverse > 0 && finalDrive > 0 && shiftTime >= 0 && efficiency > 0 && efficiency <= 1)) {
            throw new IllegalArgumentException("변속기 값 범위 오류");
        }
        for (float r : ratios) {
            if (!(r > 0)) {
                throw new IllegalArgumentException("기어비는 양수: " + Arrays.toString(ratios));
            }
        }
        ratios = ratios.clone();
    }

    public int gearCount() {
        return ratios.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GearboxSpec g && Arrays.equals(ratios, g.ratios) && reverse == g.reverse
                && finalDrive == g.finalDrive && shiftTime == g.shiftTime && efficiency == g.efficiency;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(ratios) * 31 + Float.hashCode(finalDrive);
    }

    @Override
    public String toString() {
        return "GearboxSpec" + Arrays.toString(ratios) + " R" + reverse + " final " + finalDrive;
    }
}
