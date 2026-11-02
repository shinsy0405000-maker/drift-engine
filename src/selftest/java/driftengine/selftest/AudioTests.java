package driftengine.selftest;

import driftengine.audio.CarSynth;
import driftengine.audio.SoundFrame;
import driftengine.audio.SoundProfile;
import driftengine.audio.SoundProfile.Exhaust;
import driftengine.audio.SoundProfile.Layout;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.CarCatalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static driftengine.selftest.SoundLab.*;
import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 덩어리 E: 소리 합성 (소리 장치 없이 스펙트럼으로 검사) */
final class AudioTests {

    private AudioTests() {
    }

    static void run() {
        test("소리 성격: 차 파일의 sound.* 를 읽고, 엔진 종류와 안 맞으면 오류", AudioTests::profiles);
        test("점화 주파수: 직렬4 / 로터리 2·3 / 전기 모터가 회전수에 맞는 음높이", AudioTests::firingPitch);
        test("성격: 크로스플레인 V8·박서는 반 차수 떨림이 크고, 로터리 공회전은 불규칙", AudioTests::character);
        test("크기: 풀 스로틀이 떼었을 때보다 크고, 레드존에서 폭발이 빠지고, 떼면 팝", AudioTests::loadLimiterPops);
        test("터보: 휘슬이 부스트 따라 올라가고, 떼는 순간 블로우오프", AudioTests::turbo);
        test("타이어: 미끄러지면 비명, 왼쪽 바퀴는 왼쪽 귀", AudioTests::tires);
        test("실제 주행 소리: 모든 차 NaN 없음, ±1 안, 드리프트 비명, 터보 차 블로우오프", AudioTests::driving);
        test("오디오 스레드: 가짜 장치로 실시간 출력, 차 바꿀 때 끊김 없음, 닫으면 멈춤", AudioTests::outputThread);
    }

    private static List<CarCatalog.Entry> catalog() {
        return CarCatalog.load(Path.of("cars"));
    }

    private static CarCatalog.Entry find(String id) {
        return catalog().stream().filter(e -> e.spec().id().equals(id)).findFirst().orElseThrow();
    }

    private static SoundProfile profile(String id) {
        return find(id).sound();
    }

    private static void profiles() {
        Map<String, Layout> expect = Map.ofEntries(
                Map.entry("01-kaze-rt7", Layout.ROTARY), Map.entry("02-hayate-sr15", Layout.INLINE), Map.entry("03-tora-86r", Layout.BOXER),
                Map.entry("04-hanul-coupe38", Layout.V), Map.entry("05-hanul-nvolt5", Layout.ELECTRIC), Map.entry("06-hanul-nvolt6", Layout.ELECTRIC),
                Map.entry("07-aurum-gtm", Layout.CROSSPLANE), Map.entry("08-hanul-fc74", Layout.FUELCELL), Map.entry("09-kaze-fujin", Layout.ROTARY),
                Map.entry("10-hayate-sx14", Layout.INLINE), Map.entry("11-hayate-cr33", Layout.INLINE), Map.entry("12-tora-ae8", Layout.INLINE),
                Map.entry("13-raiden-jz80", Layout.INLINE), Map.entry("14-raiden-r3x", Layout.INLINE), Map.entry("15-hayate-jx10", Layout.INLINE),
                Map.entry("16-hanul-tybur", Layout.V), Map.entry("17-hanul-coupe20t", Layout.INLINE), Map.entry("18-daon-gt33", Layout.V),
                Map.entry("19-aurum-gs3", Layout.V), Map.entry("20-hanul-ponygt", Layout.INLINE), Map.entry("21-aurum-magmax", Layout.CROSSPLANE),
                Map.entry("22-vanta-lmh", Layout.V), Map.entry("23-raiden-gt1", Layout.CROSSPLANE), Map.entry("24-tora-raijin", Layout.V));
        for (CarCatalog.Entry e : catalog()) {
            check(e.sound().layout() == expect.get(e.spec().id()), e.spec().id() + " → " + e.sound().layout());
        }
        check(profile("09-kaze-fujin").exhaust() == Exhaust.RACE, "Fujin 레이스 배기");
        CarSpec piston = CarSpec.defaults();
        Properties bad = new Properties();
        bad.setProperty("sound.layout", "electric");
        TestRunner.expectThrows(IllegalArgumentException.class,
                () -> SoundProfile.fromProperties(piston.engine(), bad), "엔진 차에 전기 소리 → 오류");
        SoundProfile d = SoundProfile.defaults(piston.engine());
        check(d.layout() == Layout.ROTARY, "기본 차(RT-7)는 로터리: " + d.layout());
        check(d.withTuneLevel(3).exhaustLevel() == 3 && d.withTuneLevel(9).exhaustLevel() == 3, "튜닝 단계 0 ~ 3");
    }

    /** 고른 간격 엔진: 점화 주파수 배음 에너지 / 그 사이(반 차수) 에너지 */
    private static double harmonicRatio(float[] mono, double f0, int count) {
        double[] sp = spectrum(mono);
        double h = 0;
        double between = 0;
        for (int k = 1; k <= count; k++) {
            h += around(sp, k * f0, 4);
            between += around(sp, (k + 0.5) * f0, 4);
        }
        return h / Math.max(1e-30, between);
    }

    /** 사이클 주파수(fc) 배수 중 점화 주파수 배수가 아닌 것의 에너지 / 점화 배수 에너지 */
    private static double subRatio(float[] mono, double fc, int perFiring, double maxHz) {
        double[] sp = spectrum(mono);
        double sub = 0;
        double fire = 0;
        for (int k = 1; k * fc <= maxHz; k++) {
            double e = around(sp, k * fc, 3);
            if (k % perFiring == 0) {
                fire += e;
            } else {
                sub += e;
            }
        }
        return sub / Math.max(1e-30, fire);
    }

    private static float[] steadyMono(SoundProfile p, float rpm, float thr) {
        float[] st = steady(synth(p), frame(rpm, thr, thr, 0, 0, false, 0, 0, 0, 1), 2.0);
        return mono(st, 0.6, 2.0);
    }

    private static void firingPitch() {
        double i4 = harmonicRatio(steadyMono(profile("02-hayate-sr15"), 3000, 0.6f), 100, 10);
        check(i4 > 4, "직렬4 3000rpm: 100 Hz 배음이 우세 (비 " + fmt(i4) + ")");
        double r2 = harmonicRatio(steadyMono(profile("01-kaze-rt7"), 6000, 0.6f), 200, 10);
        check(r2 > 4, "로터리2 6000rpm: 200 Hz 배음이 우세 (비 " + fmt(r2) + ")");
        double r3 = harmonicRatio(steadyMono(profile("09-kaze-fujin"), 6000, 0.6f), 300, 8);
        check(r3 > 4, "로터리3 6000rpm: 300 Hz 배음이 우세 (비 " + fmt(r3) + ")");
        // 전기: 모터 윙 소리 = 회전수/60 × 극쌍 4
        SoundProfile ev = profile("05-hanul-nvolt5");
        for (float rpm : new float[]{6000, 12000}) {
            float[] st = steady(synth(ev), frame(rpm, 0.6f, 0.6f, 0, 0, false, 0, 40, 0, 1), 1.5);
            double pk = peakHz(spectrum(mono(st, 0.5, 1.5)), 150, 2 * rpm / 60 * 4 * 0.75);
            double want = rpm / 60 * 4;
            check(Math.abs(pk - want) < want * 0.03, "전기 " + rpm + "rpm: 윙 " + fmt(pk) + " Hz (기대 " + fmt(want) + ")");
        }
        CarSynth evs = synth(ev);
        steady(evs, frame(8000, 1, 1, 0, 0, false, 0, 30, 0, 1), 0.5);
        check(evs.firings() == 0, "전기차는 폭발 없음");
    }

    private static void character() {
        // 크로스플레인 V8: 사이클(720°) = 3000/120 = 25 Hz, 점화 = 200 Hz (8배)
        SoundProfile v8 = profile("07-aurum-gtm");
        SoundProfile v8even = new SoundProfile(Layout.V, v8.exhaust(), v8.bov(), 8, v8.idleRpm(), v8.redlineRpm(), 0);
        // 등간격 V8 은 뱅크마다 180° 간격이라 100 Hz(뱅크 주파수) 배수에만 소리가 있다.
        // 크로스플레인은 뱅크 안 간격이 고르지 않아 25 Hz 배수 전체(1/4 차수)에 소리가 생긴다.
        double cross = subRatio(steadyMono(v8, 3000, 0.6f), 25, 4, 1600);
        double even = subRatio(steadyMono(v8even, 3000, 0.6f), 25, 4, 1600);
        check(cross > 2 * even, "크로스플레인 우르릉: 반 차수 " + fmt(cross) + " vs 등간격 V8 " + fmt(even));
        SoundProfile boxer = profile("03-tora-86r");
        SoundProfile inline = new SoundProfile(Layout.INLINE, boxer.exhaust(), true, 4, boxer.idleRpm(), boxer.redlineRpm(), 0);
        double b = subRatio(steadyMono(boxer, 3000, 0.6f), 25, 4, 1200);
        double in = subRatio(steadyMono(inline, 3000, 0.6f), 25, 4, 1200);
        check(b > 2 * in, "박서 두두두: 반 차수 " + fmt(b) + " vs 직렬 " + fmt(in));
        // 로터리 공회전: 20 ms 창 크기의 흔들림
        double rotCv = windowCv(steadyMono(profile("01-kaze-rt7"), 900, 0));
        double inCv = windowCv(steadyMono(profile("02-hayate-sr15"), 850, 0));
        check(rotCv > 1.5 * inCv, "로터리 공회전 불규칙(브랍): 흔들림 " + fmt(rotCv) + " vs 직렬 " + fmt(inCv));
    }

    private static double windowCv(float[] x) {
        int w = (int) (0.02 * SR);
        int n = x.length / w;
        double[] r = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            r[i] = rms(java.util.Arrays.copyOfRange(x, i * w, (i + 1) * w));
            mean += r[i];
        }
        mean /= n;
        double var = 0;
        for (double v : r) {
            var += (v - mean) * (v - mean);
        }
        return Math.sqrt(var / n) / mean;
    }

    private static void loadLimiterPops() {
        SoundProfile sr15 = profile("02-hayate-sr15");
        double on = rms(steadyMono(sr15, 3500, 1));
        double off = rms(steadyMono(sr15, 3500, 0));
        check(on > 1.8 * off, "풀 스로틀 " + fmt(on) + " vs 뗌 " + fmt(off));

        CarSynth a = synth(sr15);
        steady(a, frame(7500, 1, 1, 0, 0, false, 0, 0, 0, 1), 1);
        CarSynth b = synth(sr15);
        steady(b, frame(7500, 1, 1, 0, 0, true, 0, 0, 0, 1), 1);
        check(b.firings() < 0.6 * a.firings(), "레드존 연료 차단: 폭발 " + b.firings() + " vs " + a.firings());

        int rotaryPops = popsAfterLift(profile("01-kaze-rt7"), 7000);
        int stockPops = popsAfterLift(profile("03-tora-86r"), 6000);
        check(rotaryPops > 0 && rotaryPops > stockPops, "떼면 팝: 로터리 " + rotaryPops + " > 순정 박서 " + stockPops);
    }

    private static int popsAfterLift(SoundProfile p, float rpm) {
        CarSynth s = synth(p);
        steady(s, frame(rpm, 1, 1, 0, 0, false, 0, 0, 0, 1), 1);
        steady(s, frame(rpm * 0.85f, 0, 0, 0, 0, false, 0, 0, 0, 1), 1.5);
        return s.pops();
    }

    private static void turbo() {
        SoundProfile sr15 = profile("02-hayate-sr15");
        float[] spooled = steady(synth(sr15), frame(5000, 1, 1, 0.5f, 0.5f, false, 0, 0, 0, 1), 2.5);
        float[] flat = steady(synth(sr15), frame(5000, 1, 1, 0, 0.5f, false, 0, 0, 0, 1), 2.5);
        double[] sp = spectrum(mono(spooled, 1.5, 2.5));
        double hi = band(sp, 5500, 8000) / band(spectrum(mono(flat, 1.5, 2.5)), 5500, 8000);
        check(hi > 2, "부스트가 차면 5.5 ~ 8 kHz 휘슬·쉬익이 커짐 (x" + fmt(hi) + ")");
        double pk = peakHz(sp, 5000, 8500);
        check(Math.abs(pk - 7000) < 200, "최대 부스트 휘슬 ~7 kHz: " + fmt(pk));

        // 블로우오프: 떼는 순간 카운터가 1 늘면 "치익"
        double withBov = bovBurst(sr15, 1);
        double without = bovBurst(sr15, 0);
        check(withBov > 3 * without, "블로우오프 1 ~ 4 kHz 소리 x" + fmt(withBov / without));
        CarSynth s = synth(sr15);
        steady(s, frame(5000, 1, 1, 0.5f, 0.5f, false, 0, 0, 0, 1), 0.5);
        steady(s, frame(5000, 0, 0, 0.3f, 0.5f, false, 1, 0, 0, 1), 0.3);
        check(s.blowOffs() == 1, "블로우오프 1번: " + s.blowOffs());
    }

    private static double bovBurst(SoundProfile p, int count) {
        CarSynth s = synth(p);
        steady(s, frame(5000, 1, 1, 0.5f, 0.5f, false, 0, 0, 0, 1), 1);
        float[] after = steady(s, frame(4500, 0, 0, 0.3f, 0.5f, false, count, 0, 0, 1), 0.4);
        return band(spectrum(mono(after, 0.0, 0.4)), 1000, 4000);
    }

    private static void tires() {
        SoundProfile p = profile("05-hanul-nvolt5"); // 엔진 소리가 작은 전기차로 타이어만 듣기
        float[] slide = steady(synth(p), frame(3000, 0.3f, 0.3f, 0, 0, false, 0, 15, 8, 1), 1.5);
        float[] grip = steady(synth(p), frame(3000, 0.3f, 0.3f, 0, 0, false, 0, 15, 0, 1), 1.5);
        double r = band(spectrum(mono(slide, 0.5, 1.5)), 500, 2500) / band(spectrum(mono(grip, 0.5, 1.5)), 500, 2500);
        check(r > 10, "미끄러질 때 500 ~ 2500 Hz 비명 x" + fmt(r));
        SoundFrame left = new SoundFrame(3000, 0.3f, 0.3f, 0, 0, false, 0, false, 15,
                new float[]{8, 0, 8, 0}, new float[]{1, 1, 1, 1}, 1);
        float[] st = steady(synth(p), left, 1.5);
        double l = band(spectrum(channel(st, 0, 0.5, 1.5)), 500, 2500);
        double rr = band(spectrum(channel(st, 1, 0.5, 1.5)), 500, 2500);
        check(l > 1.8 * rr, "왼쪽 바퀴 비명은 왼쪽 귀가 크게 (x" + fmt(l / rr) + ")");
        float[] again = steady(synth(p), left, 1.5);
        check(java.util.Arrays.equals(st, again), "같은 입력 → 같은 소리 (재현 가능)");
    }

    private static void driving() {
        for (CarCatalog.Entry e : catalog()) {
            float[] squeal = new float[1];
            CarSynth s = synth(e.sound());
            float[] out = drive(s, e.spec(), squeal);
            String id = e.spec().id();
            check(finite(out), id + ": NaN 없음");
            check(peak(out) <= 1, id + ": ±1 안 (" + fmt(peak(out)) + ")");
            double level = rms(out);
            check(level > 0.03 && level < 0.4, id + ": 크기 " + fmt(level));
            check(squeal[0] > 0.5, id + ": 드리프트 중 타이어 비명 " + fmt(squeal[0]));
            if (e.spec().engine().turbo() != null) {
                check(s.blowOffs() >= 1, id + ": 가속하다 떼면 블로우오프 (" + s.blowOffs() + ")");
            }
        }
    }

    private static void outputThread() {
        FakeAudioLine line = new FakeAudioLine(true);
        driftengine.audio.CarAudio audio = driftengine.audio.CarAudio.startWith(line);
        audio.setCar(profile("01-kaze-rt7"));
        long start = System.nanoTime();
        try {
            for (int t = 0; t < 54; t++) { // 게임 루프 흉내: 60 Hz 로 상태를 넘김
                float rpm = 1500 + t * 120;
                audio.push(frame(rpm, 1, 1, 0.3f, 0.5f, false, 0, 10, 0, 1));
                if (t == 30) {
                    audio.setCar(profile("05-hanul-nvolt5"));
                }
                Thread.sleep(16);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        audio.close();
        check(!audio.active() && !line.isOpen(), "닫으면 스레드가 멈추고 줄이 닫힘");
        float[] st = line.samples();
        double played = st.length / 2.0 / SR;
        check(played > 0.5 * seconds && played < 1.6 * seconds + 0.1,
                String.format("실시간 속도: %.2f 초 동안 %.2f 초 분량", seconds, played));
        check(finite(st) && rms(st) > 0.01, "소리가 나옴 (크기 " + fmt(rms(st)) + ")");
        float jump = 0;
        for (int i = 2; i < st.length; i++) {
            jump = Math.max(jump, Math.abs(st[i] - st[i - 2]));
        }
        check(jump < 0.6f, "샘플 사이 큰 튐 없음 (차 바꿈 포함, 최대 " + fmt(jump) + ")");
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
