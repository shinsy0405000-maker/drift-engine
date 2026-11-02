package driftengine.world.map;

/** 맵 지역. 한 번에 한 지역만 불러온다 (지역마다 원점이 따로라 좌표가 800m 를 넘지 않음 → 기준점 이동 불필요) */
public enum Zone {
    HUB("연습장", "drift-pad"),
    CIRCUIT("용인 서킷", "yongin-circuit"),
    PASS("태백 고갯길", "taebaek-pass"),
    CITY("도시", "city-streets");

    public final String label;
    /** 클럽 이벤트 track 이름 */
    public final String track;

    Zone(String label, String track) {
        this.label = label;
        this.track = track;
    }

    /** 이벤트 코스 이름 → 지역 (모르면 연습장) */
    public static Zone ofTrack(String track) {
        for (Zone z : values()) {
            if (z.track.equals(track)) {
                return z;
            }
        }
        return HUB;
    }
}
