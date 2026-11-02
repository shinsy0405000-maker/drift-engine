package driftengine.selftest;

import driftengine.math.Quat;
import driftengine.math.Vec3;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriverInput;
import driftengine.world.LapTimer;
import driftengine.world.map.Course;
import driftengine.world.map.RoadPath;

/**
 * 컴퓨터 운전자: 서킷 중심선을 따라 (평지 시험 장치, 높낮이 무시) 한 바퀴 돌아 랩 시간을 잰다.
 * 키보드처럼 누름/안 누름만 쓴다. 조향은 앞을 보고 따라가기(pure pursuit), 속도는 굽음으로 정한 한계 속도.
 * 랩 이벤트 목표 시간을 정하고, 코스가 실제 차로 달릴 수 있는지 확인하는 데 쓴다.
 */
final class LapBot {

    /** 결과: 랩 시간 (못 돌면 -1), 도로에서 가장 멀리 벗어난 거리, 최고 속도 km/h */
    record Result(float lap, float maxOff, float topKmh, boolean nan) {
    }

    /** 타이어 그립을 얼마나 믿고 달릴지 (1 = 한계) */
    static final float CAUTION = Float.parseFloat(System.getProperty("lapbot.caution", "0.78"));
    static boolean DEBUG = Boolean.getBoolean("lapbot.debug");

    private LapBot() {
    }

    static Result run(CarSpec spec, Course course, float startBack) {
        RoadPath p = course.path();
        int n = p.count();
        float ds = p.length() / n;
        float[] vmax = speedProfile(spec, p);
        DriveSim sim = new DriveSim(spec);
        float[] st = course.behindStart(startBack);
        // 시험 장치는 원점에 차를 둔다 → 경로를 차 기준으로 옮겨 생각한다
        Vec3 origin = new Vec3(st[0], 0, st[1]);
        float yaw = driftengine.world.map.Zones.yawOf(st[2], st[3]);
        sim.car.reset(new Vec3(0, spec.restHeight() + 0.02f, 0), Quat.axisAngle(Vec3.UNIT_Y, yaw));
        sim.car.traction().setMode(driftengine.vehicle.TractionControl.Mode.FULL);
        sim.run(DriverInput.NONE, 0.5);
        LapTimer timer = new LapTimer(course);
        int idx = p.index(course.gates().get(0).s() - startBack);
        float maxOff = 0;
        float top = 0;
        float px = st[0], pz = st[1];
        float limit = 400;
        double t0 = sim.time;
        while (sim.time - t0 < limit) {
            Vec3 pos = sim.body.position().add(origin);
            // 가까운 경로 점 (지난 위치 근처만)
            float best = Float.MAX_VALUE;
            int bi = idx;
            for (int k = -5; k < 40; k++) {
                int j = wrap(p, idx + k);
                float d = (float) Math.hypot(p.x(j) - pos.x(), p.z(j) - pos.z());
                if (d < best) {
                    best = d;
                    bi = j;
                }
            }
            idx = bi;
            maxOff = Math.max(maxOff, best);
            Vec3 v = sim.car.velocity();
            float speed = (float) Math.hypot(v.x(), v.z());
            top = Math.max(top, speed * 3.6f);
            // 조향
            float look = 5 + 0.38f * speed;
            int ti = wrap(p, idx + Math.round(look / ds));
            Quat q = sim.body.rotation();
            Vec3 f = q.rotate(new Vec3(0, 0, -1));
            Vec3 r = q.rotate(new Vec3(1, 0, 0));
            float dx = p.x(ti) - pos.x(), dz = p.z(ti) - pos.z();
            float lx = dx * r.x() + dz * r.z();
            float lz = dx * f.x() + dz * f.z();
            float alpha = (float) Math.atan2(-lx, lz);
            float dist = (float) Math.hypot(lx, lz);
            float want = (float) Math.atan(2 * Math.sin(alpha) / Math.max(1, dist) * spec.wheelbase());
            float steer = sim.car.steerAngle();
            boolean left = want > steer + 0.003f;
            boolean right = want < steer - 0.003f;
            // 속도: 조금 앞의 한계 속도
            int si = wrap(p, idx + Math.round((4 + speed * 0.25f) / ds));
            float target = vmax[si];
            // 뒤가 미끄러지기 시작하면 가속을 뗀다 (드리프트용 차가 많아서)
            boolean sliding = Math.abs(sim.car.driftAngle()) > 0.07f;
            boolean throttle = speed < target - 0.5f && !sliding;
            boolean brake = speed > target + 0.8f;
            sim.tick(DriverInput.of(throttle && !brake, brake, left, right, false));
            if (DEBUG && Math.round(sim.time * 60) % 30 == 0) {
                System.out.printf("t %.1f idx %d s %.0f off %.1f v %.1f target %.1f want %.3f steer %.3f yaw-err %.2f%n", sim.time, idx, p.s(idx), best, speed, target, want, steer, alpha);
            }
            Vec3 now = sim.body.position().add(origin);
            if (timer.update(DriveSim.TICK, px, pz, now.x(), now.z())) {
                return new Result(timer.last(), maxOff, top, sim.nan);
            }
            px = now.x();
            pz = now.z();
            if (sim.nan || maxOff > 40) {
                break;
            }
        }
        return new Result(-1, maxOff, top, sim.nan);
    }

    private static int wrap(RoadPath p, int i) {
        return p.closed() ? Math.floorMod(i, p.count()) : Math.max(0, Math.min(p.count() - 1, i));
    }

    /** 굽음 한계 속도 (다운포스 포함) → 뒤에서 앞으로 제동 한계 */
    static float[] speedProfile(CarSpec spec, RoadPath p) {
        int n = p.count();
        float ds = p.length() / n;
        float grip = (spec.frontTire().grip() + spec.rearTire().grip()) / 2 * CAUTION;
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            float k = Math.max(1e-4f, Math.abs(p.curvature(i)));
            // v² k = μ g (1 + ρ CLA v² / (2 m g))  → v² = μ g / (k − μ ρ CLA / (2 m))
            float down = grip * CarSpec.AIR_DENSITY * spec.downforceArea() / (2 * spec.mass());
            float denom = Math.max(1e-4f, k - down);
            v[i] = (float) Math.min(110, Math.sqrt(grip * CarSpec.GRAVITY / denom));
        }
        float brake = grip * CarSpec.GRAVITY * Float.parseFloat(System.getProperty("lapbot.brake", "0.6"));
        for (int pass = 0; pass < 2; pass++) {
            for (int i = n - 1; i >= 0; i--) {
                int j = p.closed() ? (i + 1) % n : Math.min(n - 1, i + 1);
                v[i] = Math.min(v[i], (float) Math.sqrt(v[j] * v[j] + 2 * brake * ds));
            }
        }
        return v;
    }
}
