package driftengine.game;

/**
 * 클럽 이벤트 하나.
 *
 * @param id       저장용 고유 이름
 * @param title    표시 이름
 * @param type     드리프트 점수 / 랩타임
 * @param track    코스 이름 (drift-pad 연습장, city-streets 도시, taebaek-pass 고갯길, yongin-circuit 서킷)
 * @param target   1성 기준: 드리프트 점수(이상) 또는 랩타임 초(이하)
 * @param reward   1성 상금 (CR). 2성 +50%, 3성 +100%, 이미 받은 별만큼은 다시 안 줌
 * @param boss     클럽 마지막 대결인지
 * @param unlockCar 보스를 이기면 열리는 차 id (없으면 null)
 */
public record ClubEvent(String id, String title, Type type, String track, float target, int reward, boolean boss,
                        String unlockCar) {

    public enum Type { DRIFT_SCORE, LAP_TIME }

    /** 결과 → 별 0~3 */
    public int stars(float result) {
        if (type == Type.DRIFT_SCORE) {
            if (result >= target * 1.7f) {
                return 3;
            }
            if (result >= target * 1.3f) {
                return 2;
            }
            return result >= target ? 1 : 0;
        }
        if (result <= 0) {
            return 0;
        }
        if (result <= target * 0.94f) {
            return 3;
        }
        if (result <= target * 0.97f) {
            return 2;
        }
        return result <= target ? 1 : 0;
    }

    /** stars 개 별일 때 총 상금 */
    public int rewardFor(int stars) {
        return switch (stars) {
            case 1 -> reward;
            case 2 -> reward * 3 / 2;
            case 3 -> reward * 2;
            default -> 0;
        };
    }
}
