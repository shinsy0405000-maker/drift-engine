package driftengine.audio;

/**
 * 터보: 터빈 휘슬(축 속도에 따라 높아지는 순음) + 흡기 쉬익 + 블로우오프 "치익" (또는 BOV 없는 차의 "투투투" 서지).
 * 터빈 축은 관성이 있어 부스트가 빠져도 천천히 느려진다.
 */
final class TurboVoice {

    private final float sr;
    private final boolean bov;
    private double phase;
    private float shaft;
    private float bovEnv;
    private float bovStrength;
    private double flutterPhase;
    private int lastBlowOff = -1;
    private float lastBoostFrac;
    private final Dsp.Noise noise = new Dsp.Noise(999);
    private final Dsp.Biquad hiss = new Dsp.Biquad();
    private final Dsp.Biquad pssh = new Dsp.Biquad();
    private final Dsp.Biquad psshHp = new Dsp.Biquad();
    private final Dsp.Smooth thrS;
    private int sub;
    private int blowOffs;

    TurboVoice(SoundProfile p, float sampleRate) {
        this.sr = sampleRate;
        this.bov = p.bov();
        thrS = new Dsp.Smooth(0.05f, sr);
        psshHp.highpass(700, 0.7f, sr);
    }

    void render(SoundFrame f, float[] out, int frames) {
        if (f.boostMax() <= 0) {
            return;
        }
        float boostFrac = Dsp.clamp01(f.boost() / f.boostMax());
        if (lastBlowOff < 0) {
            lastBlowOff = f.blowOffCount();
        }
        if (f.blowOffCount() != lastBlowOff) {
            lastBlowOff = f.blowOffCount();
            bovStrength = 0.55f + 0.45f * Math.max(lastBoostFrac, boostFrac);
            bovEnv = 1;
            flutterPhase = 0;
            blowOffs++;
        }
        lastBoostFrac = boostFrac;
        float up = 1 - (float) Math.exp(-1 / (0.15f * sr));
        float down = 1 - (float) Math.exp(-1 / (0.9f * sr));
        float bovDecay = (float) Math.exp(-1 / ((bov ? 0.22f : 0.35f) * sr));
        for (int s = 0; s < frames; s++) {
            float thr = thrS.next(f.throttle());
            shaft += (boostFrac - shaft) * (boostFrac > shaft ? up : down);
            float freq = 1800 + 5200 * shaft;
            phase += 2 * Math.PI * freq / sr;
            if (phase > 2 * Math.PI) {
                phase -= 2 * Math.PI;
            }
            if ((sub++ & 31) == 0) {
                hiss.bandpass(3200 + 3000 * shaft, 1.6f, sr);
                pssh.bandpass(bov ? 1100 + 2600 * bovEnv : 500 + 400 * bovEnv, bov ? 1.2f : 2.5f, sr);
            }
            float whistle = (float) Math.sin(phase + 0.3 * Math.sin(phase * 0.5)) * 0.045f
                    * (float) Math.pow(shaft, 1.4) * (0.35f + 0.65f * thr);
            float h = hiss.process(noise.next()) * 0.06f * shaft * thr;
            float b = 0;
            if (bovEnv > 1e-4f) {
                float n = noise.next();
                if (bov) {
                    b = psshHp.process(pssh.process(n)) * 0.9f * bovEnv * bovStrength;
                } else {
                    // 컴프레서 서지: 18 Hz 로 끊기는 낮은 바람 소리
                    flutterPhase += 2 * Math.PI * 18 / sr;
                    float gate = (float) Math.max(0, Math.sin(flutterPhase));
                    b = pssh.process(n) * 1.2f * bovEnv * bovStrength * gate * gate;
                }
                bovEnv *= bovDecay;
            }
            out[2 * s] += whistle + h + b;
            out[2 * s + 1] += whistle + h + b;
        }
    }

    int blowOffs() {
        return blowOffs;
    }
}
