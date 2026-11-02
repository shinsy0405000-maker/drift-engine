package driftengine.fx;

import driftengine.scene.Effects;

/**
 * 타이어 연기 입자. 미끄러지는 바퀴에서 뿜어져 나와 부풀고, 바람에 밀리며 떠오르다 옅어진다.
 * <p>
 * 입자 수가 상한에 닿으면 새로 만들지 않는다. 지운 자리는 마지막 입자로 채운다(순서는 렌더러가 다시 정렬).
 * 난수는 씨앗이 정해진 자체 난수라 같은 입력이면 같은 결과 (테스트 재현).
 */
public final class TireSmoke {

    public static final int CAPACITY = 1600;
    public static final float RATE = 50;            // 바퀴 하나, 강도 1 일 때 초당 입자 수
    static final float WIND_X = 0.8f;
    static final float WIND_Z = 0.3f;

    private final float[] px = new float[CAPACITY];
    private final float[] py = new float[CAPACITY];
    private final float[] pz = new float[CAPACITY];
    private final float[] vx = new float[CAPACITY];
    private final float[] vy = new float[CAPACITY];
    private final float[] vz = new float[CAPACITY];
    private final float[] age = new float[CAPACITY];
    private final float[] life = new float[CAPACITY];
    private final float[] size0 = new float[CAPACITY];
    private final float[] grow = new float[CAPACITY];
    private final float[] alpha0 = new float[CAPACITY];
    private final float[] rot = new float[CAPACITY];
    private final float[] spin = new float[CAPACITY];
    private final float[] shade = new float[CAPACITY];
    private final float[] seed = new float[CAPACITY];
    private final float[] carry = new float[4];
    private int count;
    private int rnd = 0x2545F491;
    private long emitted;

    /** 강도 0 ~ 1: 크게 미끄러질 때만 (번아웃, 드리프트) */
    public static float intensity(float slideSpeed, float loadRatio) {
        return clamp01((slideSpeed - 6f) / 12f) * clamp01(loadRatio * 1.2f);
    }

    /**
     * 바퀴 하나에서 이번 틱 만큼 뿜는다.
     *
     * @param x,y,z     바퀴가 땅에 닿은 점
     * @param cvx,cvy,cvz 차 속도 (연기가 조금 따라감)
     */
    public void emit(int wheel, float x, float y, float z, float cvx, float cvy, float cvz, float intensity, float dt) {
        if (intensity <= 0) {
            carry[wheel] = 0;
            return;
        }
        carry[wheel] += RATE * intensity * dt;
        while (carry[wheel] >= 1) {
            carry[wheel] -= 1;
            if (count >= CAPACITY) {
                continue;
            }
            int i = count++;
            emitted++;
            px[i] = x + (rand() - 0.5f) * 0.3f;
            py[i] = y + 0.15f;
            pz[i] = z + (rand() - 0.5f) * 0.3f;
            vx[i] = cvx * 0.25f + (rand() - 0.5f) * 2.2f;
            vy[i] = 0.5f + rand() * 0.6f;
            vz[i] = cvz * 0.25f + (rand() - 0.5f) * 2.2f;
            age[i] = 0;
            life[i] = 2.4f + rand() * 1.6f;
            size0[i] = 0.3f + rand() * 0.2f;
            grow[i] = 1.3f + rand() * 0.8f;
            alpha0[i] = 0.18f + 0.22f * clamp01(intensity) + rand() * 0.05f;
            rot[i] = rand() * 6.2832f;
            spin[i] = (rand() - 0.5f) * 0.8f;
            shade[i] = 0.82f + rand() * 0.1f;
            seed[i] = rand();
        }
    }

    public void update(float dt, SkidMarks.Ground ground) {
        float drag = (float) Math.exp(-1.6 * dt);
        float windPull = 1 - (float) Math.exp(-0.7 * dt);
        for (int i = 0; i < count; i++) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                remove(i);
                i--;
                continue;
            }
            vx[i] = vx[i] * drag + (WIND_X - vx[i] * drag) * windPull;
            vz[i] = vz[i] * drag + (WIND_Z - vz[i] * drag) * windPull;
            vy[i] = vy[i] * drag + 0.35f * dt; // 뜨거운 공기라 천천히 뜬다
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            pz[i] += vz[i] * dt;
            rot[i] += spin[i] * dt;
            float floor = ground.height(px[i], pz[i]) + size(i) * 0.35f;
            if (py[i] < floor) {
                py[i] = floor;
                vy[i] = Math.max(vy[i], 0);
            }
        }
    }

    private float size(int i) {
        return size0[i] + grow[i] * 1.3f * (1 - (float) Math.exp(-age[i] / 1.3f));
    }

    private float alpha(int i) {
        float t = age[i] / life[i];
        float fadeIn = clamp01(age[i] / 0.15f);
        float fadeOut = (float) Math.pow(1 - t, 1.5);
        return alpha0[i] * fadeIn * fadeOut;
    }

    private void remove(int i) {
        int last = --count;
        if (i == last) {
            return;
        }
        px[i] = px[last];
        py[i] = py[last];
        pz[i] = pz[last];
        vx[i] = vx[last];
        vy[i] = vy[last];
        vz[i] = vz[last];
        age[i] = age[last];
        life[i] = life[last];
        size0[i] = size0[last];
        grow[i] = grow[last];
        alpha0[i] = alpha0[last];
        rot[i] = rot[last];
        spin[i] = spin[last];
        shade[i] = shade[last];
        seed[i] = seed[last];
    }

    public void write(Effects fx) {
        for (int i = 0; i < count; i++) {
            if (!fx.addSmoke(px[i], py[i], pz[i], size(i), alpha(i), rot[i], shade[i], seed[i])) {
                break;
            }
        }
    }

    public void clear() {
        count = 0;
        java.util.Arrays.fill(carry, 0);
    }

    public int count() {
        return count;
    }

    public long emitted() {
        return emitted;
    }

    /** 테스트용: 입자 i 의 높이·크기·진하기 */
    public float y(int i) {
        return py[i];
    }

    public float sizeOf(int i) {
        return size(i);
    }

    public float alphaOf(int i) {
        return alpha(i);
    }

    private float rand() {
        rnd ^= rnd << 13;
        rnd ^= rnd >>> 17;
        rnd ^= rnd << 5;
        return (rnd >>> 8) * (1f / (1 << 24));
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
