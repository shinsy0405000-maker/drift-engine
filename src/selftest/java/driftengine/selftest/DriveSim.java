package driftengine.selftest;

import driftengine.math.Vec3;
import driftengine.vehicle.Car;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriverInput;

/** 차 한 대를 평지에 놓고 게임과 같은 순서(틱 60Hz × 서브스텝 4)로 굴리는 시험 장치 */
final class DriveSim {

    static final float TICK = 1f / 60;
    static final int SUBSTEPS = 4;

    final CarSpec spec;
    final SimpleRigidBody body;
    final Car car;
    double time;
    boolean nan;

    DriveSim(CarSpec spec) {
        this.spec = spec;
        this.body = new SimpleRigidBody(spec, new Vec3(0, spec.restHeight() + 0.05f, 0));
        this.car = new Car(spec, body);
    }

    /** 입력을 유지한 채 seconds 초 진행 */
    void run(DriverInput input, double seconds) {
        int ticks = (int) Math.round(seconds / TICK);
        for (int t = 0; t < ticks; t++) {
            tick(input);
        }
    }

    void tick(DriverInput input) {
        car.control(input);
        float sub = TICK / SUBSTEPS;
        for (int s = 0; s < SUBSTEPS; s++) {
            car.step(sub);
            body.integrate(sub);
        }
        time += TICK;
        Vec3 p = body.position();
        if (Float.isNaN(p.x() + p.y() + p.z()) || Float.isNaN(car.engine().rpm())) {
            nan = true;
        }
    }

    /** 앞으로 가는 속도를 바로 주고 바퀴도 맞춰 굴린 상태로 (가속 시간 아끼기) */
    void launchAt(float speedMs) {
        run(DriverInput.NONE, 1.0);
        Vec3 fwd = body.rotation().rotate(new Vec3(0, 0, -1));
        body.setVelocity(fwd.scale(speedMs));
        for (int i = 0; i < 4; i++) {
            setOmega(i, speedMs / spec.wheelRadius());
        }
        int gear = 1;
        float[] ratios = spec.gearbox().ratios();
        while (gear < ratios.length && speedMs / spec.wheelRadius() * ratios[gear - 1] * spec.gearbox().finalDrive()
                * 60 / (2 * Math.PI) > spec.engine().redlineRpm() * 0.75) {
            gear++;
        }
        car.gearbox().shiftTo(gear);
        run(DriverInput.NONE, 0.3);
    }

    private void setOmega(int wheel, float omega) {
        try {
            var f = Car.WheelState.class.getDeclaredField("omega");
            f.setAccessible(true);
            f.setFloat(car.wheel(wheel), omega);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    float yawRate() {
        return body.angularVelocity().y();
    }

    float heightAboveGround() {
        return body.position().y();
    }
}
