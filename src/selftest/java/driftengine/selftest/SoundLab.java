package driftengine.selftest;

import driftengine.audio.CarSynth;
import driftengine.audio.SoundFrame;
import driftengine.audio.SoundProfile;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriverInput;
import driftengine.vehicle.Turbo;

/**
 * 소리 시험 도구: 정해진 상황(회전수 올리기, 실제 주행)을 합성해 샘플로 돌려주고, 스펙트럼을 잰다.
 * 소리 장치 없이 계산만 한다.
 */
final class SoundLab {

    static final float SR = CarSynth.SAMPLE_RATE;
    static final int PER_TICK = (int) (SR / 60); // 735

    private SoundLab() {
    }

    /** 같은 상태를 seconds 초 유지한 소리 (스테레오 섞임) */
    static float[] steady(CarSynth synth, SoundFrame f, double seconds) {
        int ticks = (int) Math.round(seconds * 60);
        float[] out = new float[ticks * PER_TICK * 2];
        float[] buf = new float[PER_TICK * 2];
        for (int t = 0; t < ticks; t++) {
            synth.render(f, buf, PER_TICK);
            System.arraycopy(buf, 0, out, t * PER_TICK * 2, PER_TICK * 2);
        }
        return out;
    }

    static SoundFrame frame(float rpm, float throttle, float load, float boost, float boostMax, boolean limiter,
                            int blowOff, float speed, float slide, float wheelLoad) {
        return new SoundFrame(rpm, throttle, load, boost, boostMax, limiter, blowOff, false, speed,
                new float[]{slide, slide, slide, slide}, new float[]{wheelLoad, wheelLoad, wheelLoad, wheelLoad}, 1);
    }

    /**
     * 제자리 회전수 올리기: 공회전 1.5초 → 풀 스로틀로 레드존까지 → 레드존 0.7초 → 스로틀 떼고 떨어짐.
     * 터보 차는 실제 Turbo 계산으로 부스트를 만든다.
     */
    static float[] revSweep(CarSynth synth, CarSpec spec) {
        var e = spec.engine();
        Turbo turbo = e.turbo() != null ? new Turbo(e.turbo()) : null;
        float idle = Math.max(e.idleRpm(), e.type() == driftengine.vehicle.EngineSpec.EngineType.ELECTRIC ? 300 : 0);
        float red = e.redlineRpm();
        double total = 7.5;
        int ticks = (int) (total * 60);
        float[] out = new float[ticks * PER_TICK * 2];
        float[] buf = new float[PER_TICK * 2];
        float rpm = idle;
        float dt = 1 / 60f;
        for (int t = 0; t < ticks; t++) {
            double time = t * dt;
            float thr;
            boolean limiter = false;
            if (time < 1.5) {
                thr = 0;
                rpm += (idle - rpm) * 0.2f;
            } else if (time < 4.5) {
                thr = 1;
                rpm = Math.min(red, rpm + (red - idle) / 2.6f * dt * (0.6f + 0.4f * rpm / red));
            } else if (time < 5.2) {
                thr = 1;
                limiter = (t / 4) % 2 == 0;
                rpm = red - (limiter ? 0 : 180);
            } else {
                thr = 0;
                rpm = idle + (rpm - idle) * (float) Math.exp(-dt / 0.7);
            }
            if (turbo != null) {
                turbo.update(dt, rpm, red, thr);
            }
            float load = thr > 0 ? 1 : 0;
            SoundFrame f = new SoundFrame(rpm, thr, load, turbo != null ? turbo.boost() : 0,
                    turbo != null ? e.turbo().maxBoost() : 0, limiter, turbo != null ? turbo.blowOffCount() : 0,
                    false, 0, new float[4], new float[]{1, 1, 1, 1}, 1);
            synth.render(f, buf, PER_TICK);
            System.arraycopy(buf, 0, out, t * PER_TICK * 2, PER_TICK * 2);
        }
        return out;
    }

    /** 실제 주행 (순수 Java 물리): 출발 풀 가속 → 떼기 → 핸드브레이크 드리프트 → 회복 */
    static float[] drive(CarSynth synth, CarSpec spec, float[] maxSqueal) {
        DriveSim sim = new DriveSim(spec);
        sim.run(DriverInput.NONE, 0.5);
        float[] buf = new float[PER_TICK * 2];
        java.util.List<float[]> parts = new java.util.ArrayList<>();
        int ticks = (int) (13.0 * 60);
        for (int t = 0; t < ticks; t++) {
            double time = t / 60.0;
            DriverInput in;
            if (time < 7) {
                in = DriverInput.of(true, false, false, false, false);
            } else if (time < 8) {
                in = DriverInput.NONE;
            } else if (time < 8.5) {
                in = DriverInput.of(false, false, true, false, time < 8.4);
            } else if (time < 11.5) {
                in = DriverInput.of(true, false, false, false, false);
            } else {
                in = DriverInput.NONE;
            }
            sim.tick(in);
            SoundFrame f = SoundFrame.of(sim.car, 1);
            synth.render(f, buf, PER_TICK);
            if (maxSqueal != null) {
                maxSqueal[0] = Math.max(maxSqueal[0], synth.lastSqueal());
            }
            parts.add(buf.clone());
        }
        float[] out = new float[parts.size() * PER_TICK * 2];
        for (int i = 0; i < parts.size(); i++) {
            System.arraycopy(parts.get(i), 0, out, i * PER_TICK * 2, PER_TICK * 2);
        }
        return out;
    }

    static CarSynth synth(SoundProfile p) {
        return new CarSynth(p, SR);
    }

    // ---------------------------------------------------------------- 분석

    /** 왼·오른 평균 (from ~ to 초) */
    static float[] mono(float[] st, double from, double to) {
        int a = (int) (from * SR);
        int b = Math.min(st.length / 2, (int) (to * SR));
        float[] m = new float[Math.max(0, b - a)];
        for (int i = 0; i < m.length; i++) {
            m[i] = 0.5f * (st[2 * (a + i)] + st[2 * (a + i) + 1]);
        }
        return m;
    }

    static float[] channel(float[] st, int ch, double from, double to) {
        int a = (int) (from * SR);
        int b = Math.min(st.length / 2, (int) (to * SR));
        float[] m = new float[Math.max(0, b - a)];
        for (int i = 0; i < m.length; i++) {
            m[i] = st[2 * (a + i) + ch];
        }
        return m;
    }

    static double rms(float[] x) {
        double s = 0;
        for (float v : x) {
            s += v * v;
        }
        return Math.sqrt(s / Math.max(1, x.length));
    }

    static float peak(float[] x) {
        float p = 0;
        for (float v : x) {
            p = Math.max(p, Math.abs(v));
        }
        return p;
    }

    static boolean finite(float[] x) {
        for (float v : x) {
            if (!Float.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** 파워 스펙트럼 (한 내림 창, 길이 = 2 의 거듭제곱으로 자름). [i] = i·SR/N Hz */
    static double[] spectrum(float[] x) {
        int n = Integer.highestOneBit(x.length);
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            re[i] = x[i] * w;
        }
        fft(re, im);
        double[] p = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            p[i] = re[i] * re[i] + im[i] * im[i];
        }
        return p;
    }

    static double binHz(double[] spec) {
        return SR / (spec.length * 2.0);
    }

    static double band(double[] spec, double lo, double hi) {
        double hz = binHz(spec);
        double s = 0;
        for (int i = (int) Math.ceil(lo / hz); i <= Math.min(spec.length - 1, (int) (hi / hz)); i++) {
            s += spec[i];
        }
        return s;
    }

    /** f 근처 ±halfWidth Hz 의 에너지 */
    static double around(double[] spec, double f, double halfWidth) {
        return band(spec, f - halfWidth, f + halfWidth);
    }

    /** lo ~ hi 에서 가장 센 주파수 */
    static double peakHz(double[] spec, double lo, double hi) {
        double hz = binHz(spec);
        int best = (int) Math.ceil(lo / hz);
        for (int i = best; i <= Math.min(spec.length - 1, (int) (hi / hz)); i++) {
            if (spec[i] > spec[best]) {
                best = i;
            }
        }
        return best * hz;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
