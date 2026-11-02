package driftengine.audio;

/**
 * 타이어 비명 + 노면 소리 + 바람 소리.
 * <p>
 * 타이어 비명: 고무가 붙었다 미끄러졌다(스틱-슬립) 하며 내는 600 ~ 2000 Hz 의 공명 잡음 + 흔들리는 순음.
 * 미끄러지는 속도가 클수록, 하중이 클수록 크다. 왼쪽 바퀴는 왼쪽 귀, 오른쪽 바퀴는 오른쪽 귀로 조금 더.
 */
final class TireVoice {

    private final float sr;
    private final Dsp.Noise noise = new Dsp.Noise(31337);
    private final Dsp.Noise wobble = new Dsp.Noise(2718);
    private final Dsp.Biquad[] band1 = {new Dsp.Biquad(), new Dsp.Biquad()};
    private final Dsp.Biquad[] band2 = {new Dsp.Biquad(), new Dsp.Biquad()};
    private final Dsp.OnePole[] scrub = {new Dsp.OnePole(), new Dsp.OnePole()};
    private final Dsp.OnePole windLp = new Dsp.OnePole();
    private final Dsp.OnePole roadLp = new Dsp.OnePole();
    private final Dsp.OnePole wobbleLp = new Dsp.OnePole();
    private final Dsp.Smooth[] squealS;
    private final Dsp.Smooth speedS;
    private final double[] tone = new double[2];
    private int sub;
    private float lastSqueal;

    TireVoice(float sampleRate) {
        this.sr = sampleRate;
        squealS = new Dsp.Smooth[]{new Dsp.Smooth(0.04f, sr), new Dsp.Smooth(0.04f, sr)};
        speedS = new Dsp.Smooth(0.1f, sr);
    }

    /** 바퀴 하나의 비명 세기 0 ~ 1 */
    static float squeal(float slide, float load) {
        return Dsp.clamp01((slide - 1.8f) / 7f) * Dsp.clamp01(load * 1.2f);
    }

    void render(SoundFrame f, float[] out, int frames) {
        float[] target = new float[2];
        for (int i = 0; i < 4; i++) {
            int side = i % 2; // 0 = 왼쪽, 1 = 오른쪽
            target[side] = Math.max(target[side], squeal(f.slide()[i], f.wheelLoad()[i]));
        }
        float wob = 0;
        for (int s = 0; s < frames; s++) {
            float v = speedS.next(f.speed());
            wob = wobbleLp.lowpass(wobble.next(), 8, sr) * 3; // 느리게 흔들리는 음높이
            if ((sub++ & 31) == 0) {
                for (int k = 0; k < 2; k++) {
                    float sq = squealS[k].value();
                    float f1 = (720 + 260 * sq) * (1 + 0.05f * wob + 0.02f * k);
                    band1[k].bandpass(f1, 14, sr);
                    band2[k].bandpass(f1 * 1.93f, 10, sr);
                }
            }
            float l = 0;
            float r = 0;
            float total = 0;
            for (int k = 0; k < 2; k++) {
                float sq = squealS[k].next(target[k]);
                total = Math.max(total, sq);
                if (sq < 1e-4f) {
                    continue;
                }
                float n = noise.next();
                float f1 = (720 + 260 * sq) * (1 + 0.05f * wob + 0.02f * k);
                tone[k] += 2 * Math.PI * f1 / sr;
                if (tone[k] > 2 * Math.PI) {
                    tone[k] -= 2 * Math.PI;
                }
                float voice = band1[k].process(n) * 2.2f + band2[k].process(n) * 1.4f
                        + (float) Math.sin(tone[k]) * 0.12f
                        + scrub[k].lowpass(n, 500, sr) * 0.35f;
                float a = 0.3f * (float) Math.pow(sq, 1.2);
                if (k == 0) {
                    l += voice * a * 0.7f;
                    r += voice * a * 0.3f;
                } else {
                    l += voice * a * 0.3f;
                    r += voice * a * 0.7f;
                }
            }
            lastSqueal = total;
            // 바람 (속도² 에 비례, 상한) + 노면 웅웅
            float windAmp = Math.min(0.12f, 0.12f * (v / 55f) * (v / 55f));
            float wind = windLp.lowpass(noise.next(), 300 + 12 * v, sr) * windAmp * 2.5f;
            float road = roadLp.lowpass(noise.next(), 110, sr) * 0.25f * Dsp.clamp01(v / 25f);
            out[2 * s] += l + wind + road;
            out[2 * s + 1] += r + wind + road;
        }
    }

    float lastSqueal() {
        return lastSqueal;
    }
}
