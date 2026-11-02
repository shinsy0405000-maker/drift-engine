package driftengine.world.map;

/**
 * 도로 한 줄: 중심선 + 폭 + 꾸밈 종류.
 *
 * @param halfWidth 포장 폭의 절반 (m). 흰 바깥 차선은 그 안쪽 0.3m
 * @param style     차선·연석 그리는 방식
 * @param rails     양쪽 가드레일 (산길)
 * @param wall      콘크리트 벽까지 거리 (중심선에서 m, 0 = 없음, 서킷)
 * @param blend     포장 끝에서 원래 지형으로 돌아가는 거리 (m)
 */
public record Road(String name, RoadPath path, float halfWidth, Style style, boolean rails, float wall, float blend) {

    public enum Style {
        /** 서킷: 흰 바깥 선, 굽은 곳 빨강·흰 연석, 출발선 */
        CIRCUIT,
        /** 산길: 흰 바깥 선 + 노란 중앙 이중선 */
        MOUNTAIN
    }

    /** 포장 끝 너머 같은 높이로 평평하게 두는 폭 (m) */
    public static final float SHOULDER = 1.5f;

    public float flatHalf() {
        return halfWidth + SHOULDER;
    }
}
