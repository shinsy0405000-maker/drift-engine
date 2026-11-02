package driftengine.audio;

/**
 * 전기차 소리: 모터 전자기 윙 소리(극 수 배음) + 감속기 기어 맞물림 소리 + 저속 보행자 경고음.
 * 수소 연료전지 차는 공기 압축기 휘잉 소리가 더해진다.
 */
final class ElectricVoice {

    private static final float POLE_PAIRS = 4;
    private static final float GEAR_TEETH = 9;

    private final float sr;
    private final boolean fuelCell;
    private final float sport;
    private double p1;
    private double p2;
    private double pg;
    private double pa;
    private double pc;
    private final Dsp.Smooth rpmS;
    private final Dsp.Smooth loadS;
    private final Dsp.Smooth speedS;
    private final Dsp.Noise noise = new Dsp.Noise(4242);
    private final Dsp.Biquad hiss = new Dsp.Biquad();
    private int sub;

    ElectricVoice(SoundProfile p, float sampleRate) {
        this.sr = sampleRate;
        this.fuelCell = p.layout() == SoundProfile.Layout.FUELCELL;
        this.sport = p.openness();
        rpmS = new Dsp.Smooth(0.03f, sr);
        loadS = new Dsp.Smooth(0.06f, sr);
        speedS = new Dsp.Smooth(0.2f, sr);
    }

    void render(SoundFrame f, float[] out, int frames) {
        for (int s = 0; s < frames; s++) {
            float rpm = rpmS.next(Math.abs(f.rpm()));
            float load = loadS.next(Math.max(f.load(), f.throttle() * 0.5f));
            float speedKmh = speedS.next(f.speed() * 3.6f);
            float fm = rpm / 60f;
            float fe = fm * POLE_PAIRS;
            float fg = fm * GEAR_TEETH;
            p1 += 2 * Math.PI * fe / sr;
            p2 += 2 * Math.PI * fe * 2 / sr;
            pg += 2 * Math.PI * fg / sr;
            if (p1 > 1e4) {
                p1 -= 2 * Math.PI * 1000;
                p2 -= 2 * Math.PI * 2000;
                pg -= 2 * Math.PI * Math.floor(pg / (2 * Math.PI));
            }
            float fade = Dsp.clamp01(fm / 25f);
            float whine = (float) (Math.sin(p1) + 0.45 * Math.sin(p2)) * (0.05f + 0.16f * load + 0.08f * sport);
            float gear = (float) Math.sin(pg) * 0.05f * (0.3f + load) * Dsp.clamp01(fg / 300f) * (fg < 9000 ? 1 : 0);
            // 저속 보행자 경고음 (30 km/h 아래)
            float avas = 0;
            float lowSpeed = Dsp.clamp01(1 - speedKmh / 30f);
            if (lowSpeed > 0) {
                pa += 2 * Math.PI * (380 + speedKmh * 8) / sr;
                avas = (float) (Math.sin(pa) + 0.3 * Math.sin(pa * 3.01)) * 0.06f * lowSpeed;
            }
            float v = whine * fade + gear + avas;
            if (fuelCell) {
                float fc = 2200 + 5000 * load;
                pc += 2 * Math.PI * fc / sr;
                if ((sub++ & 63) == 0) {
                    hiss.bandpass(fc * 0.8f, 3, sr);
                }
                v += (float) Math.sin(pc) * 0.03f * (0.3f + load) + hiss.process(noise.next()) * 0.12f * (0.2f + load);
            }
            v *= 0.55f;
            out[2 * s] += v;
            out[2 * s + 1] += v;
        }
    }
}
