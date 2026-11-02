package driftengine.audio;

/**
 * 차 한 대의 소리 전체: 엔진(또는 모터) + 터보 + 타이어·바람. 마지막에 부드러운 리미터로 ±1 안에 넣는다.
 * 오디오 스레드 하나에서만 쓴다. 테스트에서는 같은 객체로 파일에 그린다(소리 장치 없이).
 */
public final class CarSynth {

    public static final float SAMPLE_RATE = 44100;

    private final float sr;
    private final SoundProfile profile;
    private final EngineVoice engine;
    private final ElectricVoice motor;
    private final TurboVoice turbo;
    private final TireVoice tires;
    private float[] mix = new float[0];

    public CarSynth(SoundProfile profile, float sampleRate) {
        this.sr = sampleRate;
        this.profile = profile;
        if (profile.electric()) {
            engine = null;
            turbo = null;
            motor = new ElectricVoice(profile, sr);
        } else {
            engine = new EngineVoice(profile, sr);
            turbo = new TurboVoice(profile, sr);
            motor = null;
        }
        tires = new TireVoice(sr);
    }

    public SoundProfile profile() {
        return profile;
    }

    public float sampleRate() {
        return sr;
    }

    /**
     * frames 개(스테레오) 샘플을 out[0 .. 2·frames) 에 쓴다 (덮어씀).
     */
    public void render(SoundFrame f, float[] out, int frames) {
        if (mix.length < frames * 2) {
            mix = new float[frames * 2];
        }
        java.util.Arrays.fill(mix, 0, frames * 2, 0);
        float dt = frames / sr;
        if (engine != null) {
            engine.render(f, mix, frames, dt);
            turbo.render(f, mix, frames);
        } else {
            motor.render(f, mix, frames);
        }
        tires.render(f, mix, frames);
        float vol = f.volume();
        for (int i = 0; i < frames * 2; i++) {
            out[i] = Dsp.softClip(mix[i] * vol * 1.1f) * 0.92f;
        }
    }

    // ---------------------------------------------------------------- 테스트용 정보

    public int pops() {
        return engine != null ? engine.pops() : 0;
    }

    public int firings() {
        return engine != null ? engine.firings() : 0;
    }

    public int blowOffs() {
        return turbo != null ? turbo.blowOffs() : 0;
    }

    public float lastSqueal() {
        return tires.lastSqueal();
    }
}
