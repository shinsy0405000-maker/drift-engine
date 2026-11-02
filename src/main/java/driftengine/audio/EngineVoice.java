package driftengine.audio;

import driftengine.audio.SoundProfile.Layout;

/**
 * 피스톤·로터리 엔진 배기음.
 * <p>
 * 원리: 크랭크 각을 샘플마다 돌리다가 실린더(로터)가 폭발하는 각을 지나면 배기 압력 펄스를 하나 만든다.
 * 펄스는 헤더(실린더별 지연) → 뱅크별 배기관 울림(압력파 왕복) → 소음기 챔버 → 머플러 저역 통과 →
 * 꼬리관 순서로 지난다. 소리 성격은 펄스 간격(점화 순서·뱅크), 펄스 모양, 관 길이, 머플러 열림으로 정해진다.
 * <ul>
 *   <li>직렬: 고른 간격, 한 뱅크</li>
 *   <li>박서: 헤더 길이가 달라 펄스가 조금씩 밀림 → 두두두 떨림</li>
 *   <li>V (등간격): 두 뱅크, 관 길이 조금 다름</li>
 *   <li>크로스플레인 V8: 뱅크 안 간격이 고르지 않음 → 우르릉</li>
 *   <li>로터리: 크랭크(편심축) 한 바퀴에 로터마다 한 번, 포트가 갑자기 열려 날카롭고 거침, 공회전이 불규칙(브랍)</li>
 * </ul>
 */
final class EngineVoice {

    private static final float SOUND_SPEED = 470; // 뜨거운 배기가스 속 소리 속도 (m/s)

    private final float sr;
    private final SoundProfile profile;
    private final boolean rotary;

    // 점화 표 (각도 오름차순)
    private final double cycleDeg;
    private final float[] eventDeg;
    private final int[] eventCyl;
    private final int[] bankOf;
    private final int[] headerDelay; // 샘플
    private final float[] cylGain;
    private final int banks;
    private final double nominalBankGap;
    private final double[] lastBankFire = {-1e9, -1e9};

    // 성격 숫자
    private final float blowdownDeg;
    private final float attack;
    private final float pulseNoise;
    private final float idleJitter;
    private final float popChance;
    private final float drive;
    private final float level;
    private final float feedback;
    private final float[] pipeDelay;
    private final float chamberDelay;
    private final float mufflerClosed;
    private final float mufflerOpen;

    // 상태
    private double phase;
    private double base;
    private int next;
    private final int[] wait;
    private final float[] age;
    private final float[] dur;
    private final float[] amp;
    private final float[] noiseAmt;
    private final boolean[] active;
    private final Dsp.Pipe[] pipe;
    private final Dsp.Pipe[] chamber;
    private final Dsp.OnePole[] muf1;
    private final Dsp.OnePole[] muf2;
    private final Dsp.OnePole[] dc;
    private final Dsp.OnePole intakeLp = new Dsp.OnePole();
    private final Dsp.OnePole intakeHp = new Dsp.OnePole();
    private final Dsp.Noise noise = new Dsp.Noise(12345);
    private final Dsp.Noise dice = new Dsp.Noise(777);
    private final Dsp.Smooth rpmS;
    private final Dsp.Smooth loudS;
    private final Dsp.Smooth thrS;
    private float sinceHardThrottle = 99;
    private float rpmNow;
    private float loudNow;
    private boolean limiterNow;
    private float bankIn0;
    private float bankIn1;
    private float pulseEnv;
    private int pops;
    private int firings;

    EngineVoice(SoundProfile p, float sampleRate) {
        this.sr = sampleRate;
        this.profile = p;
        this.rotary = p.layout() == Layout.ROTARY;
        int n = p.cylinders();
        float o = p.openness();

        // ---------------------------------------------------------------- 점화 표
        Layout layout = p.layout();
        if (layout == Layout.CROSSPLANE && n != 8) {
            layout = Layout.V;
        }
        cycleDeg = rotary ? 360 : 720;
        eventDeg = new float[n];
        eventCyl = new int[n];
        bankOf = new int[n];
        headerDelay = new int[n];
        cylGain = new float[n];
        float step = (float) (cycleDeg / n);
        int[] crossBanks = {0, 1, 0, 1, 1, 0, 1, 0}; // LS 점화 순서 1-8-7-2-6-5-4-3 의 뱅크 (왼 0, 오른 1)
        float[] boxerMs = {0, 0.75f, 0.2f, 1.0f, 0.4f, 0.9f};
        for (int i = 0; i < n; i++) {
            eventDeg[i] = i * step;
            eventCyl[i] = i;
            bankOf[i] = switch (layout) {
                case V -> i % 2;
                case CROSSPLANE -> crossBanks[i];
                default -> 0;
            };
            float ms = layout == Layout.BOXER ? boxerMs[i % boxerMs.length] : 0.05f * ((i * 7) % 3);
            headerDelay[i] = Math.round(ms * 0.001f * sr);
            cylGain[i] = 1 + 0.04f * (float) Math.sin(i * 2.39996); // 실린더마다 조금 다름 → 자연스러운 떨림
        }
        banks = (layout == Layout.V || layout == Layout.CROSSPLANE) ? 2 : 1;
        nominalBankGap = cycleDeg / Math.max(1, n / banks);

        // ---------------------------------------------------------------- 성격
        if (rotary) {
            blowdownDeg = 150;
            attack = 0.035f;
            pulseNoise = 0.45f;
            idleJitter = 0.5f;
            popChance = 0.05f + 0.07f * o;
            drive = 2.3f + 1.2f * o;
            level = 0.42f;
        } else {
            blowdownDeg = 130;
            attack = 0.18f;
            pulseNoise = 0.12f;
            idleJitter = 0.05f;
            popChance = 0.008f + 0.045f * o;
            drive = 1.6f + 1.0f * o;
            level = 0.48f * switch (p.layout()) {
                case V -> 1.7f;          // 두 뱅크가 나뉘어 한쪽 귀 크기가 작아짐 → 맞춤
                case CROSSPLANE -> 1.35f;
                case BOXER -> 1.3f;
                default -> 1f;
            };
        }
        feedback = -(0.36f + 0.24f * o);
        float pipeLen = (rotary ? 2.3f : 2.7f) - 1.0f * o;
        pipeDelay = new float[]{2 * pipeLen / SOUND_SPEED * sr, 2 * pipeLen * 1.08f / SOUND_SPEED * sr};
        chamberDelay = 2 * 0.45f / SOUND_SPEED * sr;
        mufflerClosed = (rotary ? 900 : 650) + 1600 * o;
        mufflerOpen = (rotary ? 3200 : 2000) + 5000 * o;

        wait = new int[n];
        age = new float[n];
        dur = new float[n];
        amp = new float[n];
        noiseAmt = new float[n];
        active = new boolean[n];
        java.util.Arrays.fill(wait, -1);
        pipe = new Dsp.Pipe[2];
        chamber = new Dsp.Pipe[2];
        muf1 = new Dsp.OnePole[2];
        muf2 = new Dsp.OnePole[2];
        dc = new Dsp.OnePole[2];
        for (int b = 0; b < 2; b++) {
            pipe[b] = new Dsp.Pipe((int) (pipeDelay[1] + 8));
            chamber[b] = new Dsp.Pipe((int) (chamberDelay + 8));
            muf1[b] = new Dsp.OnePole();
            muf2[b] = new Dsp.OnePole();
            dc[b] = new Dsp.OnePole();
        }
        rpmS = new Dsp.Smooth(0.02f, sr);
        loudS = new Dsp.Smooth(0.03f, sr);
        thrS = new Dsp.Smooth(0.05f, sr);
        next = 0;
        base = 0;
        phase = 0;
    }

    /** out 에 [L, R, L, R ...] 로 더한다 */
    void render(SoundFrame f, float[] out, int frames, float dtBlock) {
        float targetRpm = Math.max(0, f.rpm());
        // 중립에서 스로틀만 밟아도 크게, 엔진 브레이크(스로틀 0)는 작게
        float targetLoud = Math.max(f.load(), f.throttle() * 0.7f);
        if (f.throttle() > 0.6f) {
            sinceHardThrottle = 0;
        } else {
            sinceHardThrottle += dtBlock;
        }
        limiterNow = f.limiter();
        float o = profile.openness();
        for (int s = 0; s < frames; s++) {
            rpmNow = rpmS.next(targetRpm);
            loudNow = loudS.next(targetLoud);
            float thr = thrS.next(f.throttle());
            double degPerSample = rpmNow / 60.0 * 360.0 / sr;
            phase += degPerSample;
            while (phase >= base + eventDeg[next]) {
                fire(eventCyl[next], (float) degPerSample, f.throttle());
                next++;
                if (next == eventDeg.length) {
                    next = 0;
                    base += cycleDeg;
                }
            }
            if (base > 1e7) {
                lastBankFire[0] -= base;
                lastBankFire[1] -= base;
                phase -= base;
                base = 0;
            }

            // 펄스 → 뱅크 입력
            bankIn0 = 0;
            bankIn1 = 0;
            float env = 0;
            for (int c = 0; c < wait.length; c++) {
                if (wait[c] >= 0) {
                    if (wait[c] == 0) {
                        active[c] = true;
                        age[c] = 0;
                    }
                    wait[c]--;
                }
                if (!active[c]) {
                    continue;
                }
                float x = age[c] / dur[c];
                if (x >= 1) {
                    active[c] = false;
                    continue;
                }
                float shape;
                if (x < attack) {
                    float r = (float) Math.sin(Math.PI * 0.5 * x / attack);
                    shape = r * r;
                } else {
                    float t = (x - attack) / (1 - attack);
                    shape = (float) Math.exp(-5 * t) * (1 - t);
                }
                float v = amp[c] * shape * (1 + noiseAmt[c] * noise.next());
                if (bankOf[c] == 0) {
                    bankIn0 += v;
                } else {
                    bankIn1 += v;
                }
                env += amp[c] * shape;
                age[c] += 1;
            }
            pulseEnv = env;

            // 배기관 → 챔버 → 머플러 → 꼬리관
            float cut = mufflerClosed + (mufflerOpen - mufflerClosed) * Math.max(thr, loudNow);
            cut *= 0.8f + 0.4f * Math.min(1, rpmNow / profile.redlineRpm());
            float damp = 2500 + 3000 * o;
            float y0 = bank(0, bankIn0, cut, damp);
            float y1 = banks == 2 ? bank(1, bankIn1, cut, damp) : y0;

            // 흡기: 맥동에 맞춘 잡음 (스로틀 열수록)
            float intake = intakeHp.highpass(intakeLp.lowpass(noise.next(), 900, sr), 250, sr) * env * thr * 0.35f;

            float l = 0.62f * y0 + 0.38f * y1 + intake;
            float r = 0.38f * y0 + 0.62f * y1 + intake;
            float g = level * (0.35f + 0.65f * loudNow);
            out[2 * s] += Dsp.softClip(drive * l) * g;
            out[2 * s + 1] += Dsp.softClip(drive * r) * g;
        }
    }

    private float bank(int b, float x, float cut, float damp) {
        float y = pipe[b].process(x, pipeDelay[b], feedback, damp, sr);
        y = chamber[b].process(y, chamberDelay, 0.4f, 4000, sr) * 0.6f;
        y = muf1[b].lowpass(y, cut, sr);
        y = muf2[b].lowpass(y, cut * 1.5f, sr);
        return dc[b].highpass(y, 30, sr);
    }

    private void fire(int c, float degPerSample, float throttle) {
        float rpmFrac = Math.min(1, rpmNow / profile.redlineRpm());
        float idleness = Dsp.clamp01(1 - (rpmNow - profile.idleRpm()) / 1500f);
        // 레드존: 연료를 끊어 폭발이 빠짐 → 다다닥
        if (limiterNow && dice.unit() < 0.6f) {
            return;
        }
        // 공회전 떨림 (로터리는 크게, 가끔 빠짐 = 브랍)
        float jitter = idleJitter * idleness;
        if (jitter > 0.2f && dice.unit() < jitter * 0.35f) {
            return;
        }
        firings++;
        // 같은 뱅크에서 앞 펄스와 가까우면 압력이 겹쳐 세지고, 멀면 약해진다 (크로스플레인 V8 의 우르릉)
        int b = bankOf[c];
        double gap = phase - lastBankFire[b];
        lastBankFire[b] = phase;
        float pile = 1 + 0.5f * Math.max(-0.5f, Math.min(1, (float) ((nominalBankGap - gap) / nominalBankGap)));
        float a = (0.25f + 0.75f * loudNow) * cylGain[c] * pile * (1 - jitter * dice.unit());
        float d = blowdownDeg / Math.max(1e-4f, degPerSample);
        float na = pulseNoise * (0.6f + 0.4f * loudNow);
        // 감속 팝: 세게 밟다 뗀 직후, 회전수가 높을 때
        float popWindow = Dsp.clamp01(1 - sinceHardThrottle / 2.0f);
        if (throttle < 0.1f && rpmFrac > 0.35f && dice.unit() < popChance * popWindow * popWindow) {
            a *= 2.2f + dice.unit();
            d *= 1.8f;
            na = 0.9f;
            pops++;
        }
        dur[c] = Math.max(0.0006f * sr, Math.min(0.02f * sr, d));
        amp[c] = a;
        noiseAmt[c] = na;
        if (headerDelay[c] == 0) {
            active[c] = true;
            age[c] = 0;
            wait[c] = -1;
        } else {
            wait[c] = headerDelay[c];
        }
    }

    int pops() {
        return pops;
    }

    int firings() {
        return firings;
    }

    float pulseEnvelope() {
        return pulseEnv;
    }
}
