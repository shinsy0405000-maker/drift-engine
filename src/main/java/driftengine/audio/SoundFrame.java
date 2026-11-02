package driftengine.audio;

import driftengine.vehicle.Car;
import driftengine.vehicle.CarSpec;

/**
 * 게임(물리) 스레드가 오디오 스레드에 넘기는 차 상태 한 장. 바뀌지 않는 값이라 그대로 넘겨도 안전하다.
 *
 * @param rpm          엔진(모터) 회전수
 * @param throttle     스로틀 0 ~ 1
 * @param load         엔진 부하 0 ~ 1 (지금 토크 / 최대 토크)
 * @param boost        지금 부스트 (bar)
 * @param boostMax     최대 부스트 (터보 없으면 0)
 * @param limiter      레드존 연료 차단 중
 * @param blowOffCount 지금까지 블로우오프 횟수 (늘면 "치익")
 * @param shifting     변속 중
 * @param speed        차 속도 (m/s)
 * @param slide        바퀴별 미끄러지는 속도 (m/s, 앞왼 앞오 뒤왼 뒤오)
 * @param wheelLoad    바퀴별 하중 / 정지 하중 (0 = 뜸)
 * @param volume       전체 음량 0 ~ 1
 */
public record SoundFrame(float rpm, float throttle, float load, float boost, float boostMax, boolean limiter,
                         int blowOffCount, boolean shifting, float speed, float[] slide, float[] wheelLoad,
                         float volume) {

    public static final SoundFrame SILENT = new SoundFrame(0, 0, 0, 0, 0, false, 0, false, 0,
            new float[4], new float[4], 0);

    public static SoundFrame of(Car car, float volume) {
        CarSpec spec = car.spec();
        float[] slide = new float[4];
        float[] load = new float[4];
        for (int i = 0; i < 4; i++) {
            Car.WheelState w = car.wheel(i);
            slide[i] = w.slideSpeed();
            load[i] = w.load() / Math.max(1, spec.staticLoad(i));
        }
        var engine = car.engine();
        float peak = Math.max(1, spec.engine().peakTorque());
        float engineLoad = Math.max(0, Math.min(1, engine.torque() / peak));
        var turbo = engine.turbo();
        return new SoundFrame(engine.rpm(), car.throttle(), engineLoad, engine.boost(),
                turbo != null ? turbo.spec().maxBoost() : 0, engine.limiter(),
                turbo != null ? turbo.blowOffCount() : 0, car.gearbox().shifting(),
                car.velocity().length(), slide, load, volume);
    }
}
