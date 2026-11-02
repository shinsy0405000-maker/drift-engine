package driftengine.vehicle;

/**
 * 터보차저.
 *
 * @param maxBoost      최대 부스트 (bar, 대기압 위). 스톡 터보 차는 0.5 안팎, 튜닝으로 올린다
 * @param spoolStartRpm 이 회전수부터 부스트가 생긴다
 * @param fullBoostRpm  이 회전수 이상이면 최대 부스트까지 찰 수 있다
 * @param spoolTime     최대 회전수·풀 스로틀일 때 목표 부스트의 63% 까지 차는 시간 (초). 클수록 터보 랙이 길다
 * @param torquePerBar  부스트 1bar 당 토크 증가 비율 (0.85 → 1bar 에서 1.85배)
 */
public record TurboSpec(float maxBoost, float spoolStartRpm, float fullBoostRpm, float spoolTime, float torquePerBar) {

    public TurboSpec {
        if (!(maxBoost > 0 && fullBoostRpm > spoolStartRpm && spoolStartRpm >= 0 && spoolTime > 0 && torquePerBar > 0)) {
            throw new IllegalArgumentException("터보 값 범위 오류: " + this);
        }
    }

    public TurboSpec withBoost(float boost) {
        return new TurboSpec(boost, spoolStartRpm, fullBoostRpm, spoolTime, torquePerBar);
    }
}
