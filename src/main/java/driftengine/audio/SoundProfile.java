package driftengine.audio;

import driftengine.vehicle.EngineSpec;
import driftengine.vehicle.EngineSpec.EngineType;

import java.util.Locale;
import java.util.Properties;

/**
 * 차 소리 성격. 차 파일의 sound.* 키로 정하고, 없으면 엔진 종류·실린더 수로 고른다.
 * <ul>
 *   <li>sound.layout = inline | boxer | v | crossplane | rotary | electric | fuelcell</li>
 *   <li>sound.exhaust = stock | sport | race (머플러가 얼마나 열려 있나)</li>
 *   <li>sound.bov = true | false (false 면 블로우오프 대신 "투투투" 서지)</li>
 * </ul>
 *
 * @param cylinders 실린더(로터) 수
 * @param idleRpm   공회전 (로터리 공회전 떨림 계산용)
 * @param exhaustLevel 0 = 순정 ~ 3 = 가장 열림 (엔진 튜닝 단계가 더해짐)
 */
public record SoundProfile(Layout layout, Exhaust exhaust, boolean bov, int cylinders, float idleRpm,
                           float redlineRpm, int exhaustLevel) {

    public enum Layout { INLINE, BOXER, V, CROSSPLANE, ROTARY, ELECTRIC, FUELCELL }

    public enum Exhaust { STOCK, SPORT, RACE }

    public SoundProfile {
        cylinders = Math.max(1, Math.min(12, cylinders));
        exhaustLevel = Math.max(0, Math.min(3, exhaustLevel));
    }

    /** 엔진 제원만으로 고른 기본 성격 */
    public static SoundProfile defaults(EngineSpec engine) {
        return fromProperties(engine, new Properties());
    }

    public static SoundProfile fromProperties(EngineSpec engine, Properties p) {
        Layout layout = switch (engine.type()) {
            case ROTARY -> Layout.ROTARY;
            case ELECTRIC -> Layout.ELECTRIC;
            case PISTON -> engine.cylinders() >= 8 ? Layout.CROSSPLANE
                    : engine.cylinders() == 6 ? Layout.V : Layout.INLINE;
        };
        String l = p.getProperty("sound.layout");
        if (l != null) {
            layout = Layout.valueOf(l.trim().toUpperCase(Locale.ROOT));
        }
        boolean electric = layout == Layout.ELECTRIC || layout == Layout.FUELCELL;
        if (electric != (engine.type() == EngineType.ELECTRIC)) {
            throw new IllegalArgumentException("sound.layout=" + layout + " 이 엔진 종류 " + engine.type() + " 와 맞지 않습니다");
        }
        if ((layout == Layout.ROTARY) != (engine.type() == EngineType.ROTARY)) {
            throw new IllegalArgumentException("로터리 소리는 로터리 엔진에만: " + layout + " / " + engine.type());
        }
        Exhaust exhaust = Exhaust.valueOf(p.getProperty("sound.exhaust", "stock").trim().toUpperCase(Locale.ROOT));
        boolean bov = Boolean.parseBoolean(p.getProperty("sound.bov", "true").trim());
        return new SoundProfile(layout, exhaust, bov, engine.cylinders(), engine.idleRpm(), engine.redlineRpm(), 0);
    }

    /** 엔진 튜닝 단계만큼 배기가 더 열린 소리 */
    public SoundProfile withTuneLevel(int engineLevel) {
        return new SoundProfile(layout, exhaust, bov, cylinders, idleRpm, redlineRpm, engineLevel);
    }

    public boolean electric() {
        return layout == Layout.ELECTRIC || layout == Layout.FUELCELL;
    }

    /** 머플러 열림 0 ~ 1 (순정 0, 레이스 + 튜닝 끝 1) */
    float openness() {
        float base = switch (exhaust) {
            case STOCK -> 0.1f;
            case SPORT -> 0.4f;
            case RACE -> 0.75f;
        };
        return Math.min(1, base + exhaustLevel * 0.1f);
    }
}
