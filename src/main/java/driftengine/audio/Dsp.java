package driftengine.audio;

/**
 * 소리 합성용 작은 부품들: 잡음, 1차 필터, 2차(바이쿼드) 필터, 지연선.
 * 모두 한 샘플씩 처리하고, 오디오 스레드 하나에서만 쓴다 (동기화 없음).
 */
final class Dsp {

    private Dsp() {
    }

    /** 빠른 흰 잡음 (xorshift32), -1 ~ 1. 씨앗이 같으면 같은 소리 → 테스트가 재현된다 */
    static final class Noise {
        private int s;

        Noise(int seed) {
            s = seed == 0 ? 0x9E3779B9 : seed;
        }

        float next() {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            return (s >> 8) * (1f / (1 << 23));
        }

        /** 0 ~ 1 */
        float unit() {
            return (next() + 1) * 0.5f;
        }
    }

    /** 1차 저역 통과 (차단 주파수를 샘플마다 바꿔도 안전) */
    static final class OnePole {
        private float y;

        float lowpass(float x, float cutoffHz, float sampleRate) {
            float a = coef(cutoffHz, sampleRate);
            y += a * (x - y);
            return y;
        }

        float highpass(float x, float cutoffHz, float sampleRate) {
            return x - lowpass(x, cutoffHz, sampleRate);
        }

        static float coef(float cutoffHz, float sampleRate) {
            float c = Math.min(cutoffHz, sampleRate * 0.45f);
            return 1 - (float) Math.exp(-2 * Math.PI * c / sampleRate);
        }

        void reset() {
            y = 0;
        }
    }

    /** 값이 갑자기 바뀌어 "딱" 소리가 나지 않게 부드럽게 따라가는 값 */
    static final class Smooth {
        private float value;
        private final float rate;
        private boolean first = true;

        /** @param timeSec 63% 따라가는 시간 */
        Smooth(float timeSec, float sampleRate) {
            this.rate = 1 - (float) Math.exp(-1 / (timeSec * sampleRate));
        }

        float next(float target) {
            if (first) {
                value = target;
                first = false;
            }
            value += rate * (target - value);
            return value;
        }

        float value() {
            return value;
        }
    }

    /**
     * 2차 필터 (RBJ 쿡북, 직접형 II 전치). set* 로 계수를 정하고 process 로 거른다.
     * 계수 계산이 무거우니 블록마다 한 번만 바꾼다.
     */
    static final class Biquad {
        private float b0, b1, b2, a1, a2;
        private float z1, z2;

        Biquad bandpass(float freq, float q, float sampleRate) {
            double w = 2 * Math.PI * Math.min(freq, sampleRate * 0.45) / sampleRate;
            double alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha;
            set(alpha / a0, 0, -alpha / a0, -2 * Math.cos(w) / a0, (1 - alpha) / a0);
            return this;
        }

        Biquad lowpass(float freq, float q, float sampleRate) {
            double w = 2 * Math.PI * Math.min(freq, sampleRate * 0.45) / sampleRate;
            double alpha = Math.sin(w) / (2 * q);
            double cos = Math.cos(w);
            double a0 = 1 + alpha;
            set((1 - cos) / 2 / a0, (1 - cos) / a0, (1 - cos) / 2 / a0, -2 * cos / a0, (1 - alpha) / a0);
            return this;
        }

        Biquad highpass(float freq, float q, float sampleRate) {
            double w = 2 * Math.PI * Math.min(freq, sampleRate * 0.45) / sampleRate;
            double alpha = Math.sin(w) / (2 * q);
            double cos = Math.cos(w);
            double a0 = 1 + alpha;
            set((1 + cos) / 2 / a0, -(1 + cos) / a0, (1 + cos) / 2 / a0, -2 * cos / a0, (1 - alpha) / a0);
            return this;
        }

        private void set(double nb0, double nb1, double nb2, double na1, double na2) {
            b0 = (float) nb0;
            b1 = (float) nb1;
            b2 = (float) nb2;
            a1 = (float) na1;
            a2 = (float) na2;
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            if (!Float.isFinite(y)) { // 계수를 바꾸다 튀면 조용히 되살린다
                z1 = 0;
                z2 = 0;
                return 0;
            }
            return y;
        }

        void reset() {
            z1 = 0;
            z2 = 0;
        }
    }

    /**
     * 배기관 울림: 관 안을 오가는 압력파. y = x + g · 저역(y[n - D]).
     * 열린 관 끝은 압력파를 뒤집어 돌려보내므로 g 는 음수 (홀수 배음 울림).
     */
    static final class Pipe {
        private final float[] buf;
        private int pos;
        private final OnePole damp = new OnePole();

        Pipe(int maxSamples) {
            buf = new float[maxSamples];
        }

        /**
         * @param delaySamples 왕복 시간 (샘플, 소수 가능)
         * @param feedback     반사 계수 (-0.95 ~ 0.95)
         * @param dampHz       반사될 때 깎이는 높은 소리
         */
        float process(float x, float delaySamples, float feedback, float dampHz, float sampleRate) {
            float d = Math.max(1, Math.min(buf.length - 2, delaySamples));
            float rp = pos - d;
            if (rp < 0) {
                rp += buf.length;
            }
            int i0 = (int) rp;
            int i1 = i0 + 1 == buf.length ? 0 : i0 + 1;
            float frac = rp - i0;
            float delayed = buf[i0] + (buf[i1] - buf[i0]) * frac;
            float y = x + feedback * damp.lowpass(delayed, dampHz, sampleRate);
            buf[pos] = y;
            pos = pos + 1 == buf.length ? 0 : pos + 1;
            return y;
        }

        void reset() {
            java.util.Arrays.fill(buf, 0);
            damp.reset();
        }
    }

    /** 부드러운 포화 (tanh 근사, |x| 가 커도 ±1 을 넘지 않음) */
    static float softClip(float x) {
        if (x > 3) {
            return 1;
        }
        if (x < -3) {
            return -1;
        }
        float x2 = x * x;
        return x * (27 + x2) / (27 + 9 * x2);
    }

    static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
