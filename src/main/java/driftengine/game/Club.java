package driftengine.game;

import java.util.List;

/**
 * 클럽: 드리프트 클럽(드리프트 점수)과 레이싱 클럽(서킷 랩타임, 서킷 전용).
 *
 * @param maxClass 참가할 수 있는 최고 클래스 (차가 이보다 높으면 참가 불가)
 */
public record Club(String id, String name, Kind kind, CarClass maxClass, List<ClubEvent> events) {

    public enum Kind { DRIFT, RACING }

    public Club {
        events = List.copyOf(events);
    }

    /** 기본 클럽 목록. 서킷 차(FC-74, GT-M, Fujin-R, Raijin, GT1-R, Magma-X, LMH-1)는 레이싱 클럽 보스 보상 */
    public static List<Club> defaults() {
        return List.of(
                new Club("alley", "골목 드리프터즈", Kind.DRIFT, CarClass.C2, List.of(
                        drift("alley-1", "첫 미끄럼", "drift-pad", 8_000, 3_000),
                        drift("alley-2", "원 돌리기", "drift-pad", 15_000, 5_000),
                        drift("alley-3", "도심 주차장", "city-streets", 18_000, 6_000),
                        boss("alley-boss", "골목 대장", "drift-pad", 25_000, 12_000, null, ClubEvent.Type.DRIFT_SCORE))),
                new Club("taebaek", "태백 다운힐", Kind.DRIFT, CarClass.C4, List.of(
                        drift("taebaek-1", "안개 고개", "taebaek-pass", 30_000, 9_000),
                        drift("taebaek-2", "헤어핀 연속", "taebaek-pass", 45_000, 14_000),
                        boss("taebaek-boss", "태백의 왕", "taebaek-pass", 70_000, 30_000, null, ClubEvent.Type.DRIFT_SCORE))),
                new Club("nlab", "N 드리프트 랩", Kind.DRIFT, CarClass.CS, List.of(
                        drift("nlab-1", "고속 스위퍼", "drift-pad", 60_000, 20_000),
                        drift("nlab-2", "탠덤 연습", "taebaek-pass", 90_000, 30_000),
                        boss("nlab-boss", "랩 마스터", "taebaek-pass", 130_000, 60_000, null, ClubEvent.Type.DRIFT_SCORE))),
                new Club("yongin", "용인 서킷 클럽", Kind.RACING, CarClass.C5, List.of(
                        lap("yongin-1", "첫 랩", 126, 15_000),
                        lap("yongin-2", "타임 어택", 117, 25_000),
                        boss("yongin-boss", "서킷 에이스", "yongin-circuit", 111, 50_000, "08-hanul-fc74", ClubEvent.Type.LAP_TIME))),
                new Club("hypergt", "하이퍼 GT 클럽", Kind.RACING, CarClass.CS, List.of(
                        lap("hypergt-1", "GT 예선", 107, 40_000),
                        boss("hypergt-boss", "GT 챔피언", "yongin-circuit", 102, 90_000, "07-aurum-gtm", ClubEvent.Type.LAP_TIME))),
                new Club("proto", "프로토타입 리그", Kind.RACING, CarClass.CS_PLUS, List.of(
                        lap("proto-1", "르망 연습", 99, 70_000),
                        boss("proto-boss", "프로토 챔피언", "yongin-circuit", 95, 150_000, "09-kaze-fujin", ClubEvent.Type.LAP_TIME))),
                new Club("legend", "레전드 JDM", Kind.DRIFT, CarClass.C3, List.of(
                        drift("legend-1", "90년대의 밤", "drift-pad", 20_000, 7_000),
                        drift("legend-2", "직렬6 의 노래", "taebaek-pass", 38_000, 12_000),
                        boss("legend-boss", "전설의 86", "taebaek-pass", 60_000, 28_000, null, ClubEvent.Type.DRIFT_SCORE))),
                new Club("midship", "미드십 챌린지", Kind.RACING, CarClass.CS, List.of(
                        lap("midship-1", "V10 예선", 106, 45_000),
                        boss("midship-boss", "미드십 킹", "yongin-circuit", 101, 100_000, "24-tora-raijin", ClubEvent.Type.LAP_TIME))),
                new Club("gt1", "GT1 레전드", Kind.RACING, CarClass.CS_PLUS, List.of(
                        lap("gt1-1", "GT1 예선", 98, 80_000),
                        boss("gt1-boss", "GT1 챔피언", "yongin-circuit", 94, 170_000, "23-raiden-gt1", ClubEvent.Type.LAP_TIME))),
                new Club("hyper", "하이퍼카 리그", Kind.RACING, CarClass.CS_PLUS, List.of(
                        lap("hyper-1", "하이퍼 예선", 97, 90_000),
                        boss("hyper-boss", "하이퍼 챔피언", "yongin-circuit", 93, 190_000, "21-aurum-magmax", ClubEvent.Type.LAP_TIME))),
                new Club("endurance", "엔듀런스 프로토", Kind.RACING, CarClass.CS_PLUS, List.of(
                        lap("endurance-1", "야간 스틴트", 95, 110_000),
                        boss("endurance-boss", "르망의 전설", "yongin-circuit", 90, 250_000, "22-vanta-lmh", ClubEvent.Type.LAP_TIME))));
    }

    private static ClubEvent drift(String id, String title, String track, float target, int reward) {
        return new ClubEvent(id, title, ClubEvent.Type.DRIFT_SCORE, track, target, reward, false, null);
    }

    private static ClubEvent lap(String id, String title, float seconds, int reward) {
        return new ClubEvent(id, title, ClubEvent.Type.LAP_TIME, "yongin-circuit", seconds, reward, false, null);
    }

    private static ClubEvent boss(String id, String title, String track, float target, int reward, String unlock,
                                  ClubEvent.Type type) {
        return new ClubEvent(id, title, type, track, target, reward, true, unlock);
    }
}
