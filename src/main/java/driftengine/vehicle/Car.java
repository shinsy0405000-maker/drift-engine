package driftengine.vehicle;

import driftengine.math.Quat;
import driftengine.math.Vec3;

/**
 * 차량 한 대: 서스펜션 + 타이어 + 엔진·터보 + 변속기 + 차동(LSD) + 브레이크 + 조향(카운터 보조) + 공기저항.
 * <p>
 * 차체 강체(VehicleBody)는 움직임과 충돌만 맡고, 나머지 힘은 전부 여기(Java)서 계산해 물리 스텝 직전에 넣는다.
 * 호출 순서: 틱마다 control(입력) → 서브스텝마다 step(dt) → 강체 스텝.
 * <p>
 * 바퀴 회전은 타이어 반력을 "암시적"으로 적분한다: 타이어 힘의 기울기(dFx/dω)를 관성에 더해서,
 * 바퀴 관성이 작아도 240Hz 에서 진동하지 않는다. 저속에서는 슬립 계산의 분모를 VMIN 아래로 내리지 않는다
 * (속도 0 근처에서 0 으로 나누기 방지 → 정지 시 타이어가 점성 마찰처럼 차를 붙잡는다).
 */
public final class Car {

    static final float VMIN_LONG = 2.0f;
    static final float VMIN_LAT = 2.0f;
    static final float ROLLING_RESISTANCE = 0.012f;
    static final float RAD_PER_SEC_TO_RPM = (float) (60 / (2 * Math.PI));
    private static final Vec3 LOCAL_FORWARD = new Vec3(0, 0, -1);
    private static final Vec3 LOCAL_RIGHT = new Vec3(1, 0, 0);
    private static final Vec3 LOCAL_UP = new Vec3(0, 1, 0);

    /** 바퀴 하나의 이번 스텝 상태 (그리기·표시·테스트용) */
    public static final class WheelState {
        boolean contact;
        float compression;
        float load;
        float hitDistance;
        float omega;
        float spin;
        float slipRatio;
        float slipAngle;
        float fx;
        float fy;
        float slideSpeed;
        Vec3 contactPoint = Vec3.ZERO;

        public boolean contact() {
            return contact;
        }

        public float compression() {
            return compression;
        }

        /** 바퀴 하중 (N) */
        public float load() {
            return load;
        }

        /** 이전 버전 호환: 서스펜션 힘 = 하중 */
        public float force() {
            return load;
        }

        public float hitDistance() {
            return hitDistance;
        }

        /** 바퀴 회전 속도 (rad/s, + = 앞으로 굴러감) */
        public float omega() {
            return omega;
        }

        /** 누적 회전각 (그리기용, rad) */
        public float spin() {
            return spin;
        }

        public float slipRatio() {
            return slipRatio;
        }

        public float slipAngle() {
            return slipAngle;
        }

        public float fx() {
            return fx;
        }

        public float fy() {
            return fy;
        }

        /** 타이어 접지면이 땅 위를 미끄러지는 속도 (m/s). 소리·연기·스키드 자국 세기 */
        public float slideSpeed() {
            return slideSpeed;
        }

        /** 땅에 닿은 점 (월드). 닿지 않았으면 마지막 값 */
        public Vec3 contactPoint() {
            return contactPoint;
        }
    }

    private final CarSpec spec;
    private final VehicleBody body;
    private final WheelState[] wheels = new WheelState[4];
    private final Engine engine;
    private final Gearbox gearbox;
    private final TractionControl traction = new TractionControl();
    /** 캠버를 반영한 타이어 (바퀴별) + 앞뒤/옆 그립 배율 */
    private final TireSpec[] tires = new TireSpec[4];
    private final float[] camberGripX = new float[4];
    private final float[] camberGripY = new float[4];
    private final Quat[] camberTilt = new Quat[4];
    // 니트로: 통 잔량 0~1
    private float nitroTank = 1f;
    private boolean nitroOn;

    private DriverInput input = DriverInput.NONE;
    private float throttle;
    private float brake;
    private boolean handbrake;
    private float steer;
    private float standTimer;

    // 표시용
    private float forwardSpeed;
    private float driftAngle;
    private float slip;          // β: 차가 보는 방향 ~ 움직이는 방향 (rad, + = 움직임이 왼쪽 = 차가 오른쪽으로 돌아감)
    private float slipRate;      // dβ/dt (거른 값)
    private float slipPredicted; // 0.25초 뒤 예상 β (보조가 미리 반응하게)
    private float speed;

    // 스텝마다 재사용
    private final Vec3[] mounts = new Vec3[4];
    private final float[] suspension = new float[4];
    private final float[] newOmega = new float[4];
    private final float[] effInertia = new float[4];
    private final float[] contactVx = new float[4];
    private final float[] tireOut = new float[2];
    private final float[] tireOut2 = new float[2];
    private final float[] pair = new float[2];
    private final float[] pairInertia = new float[2];

    public Car(CarSpec spec, VehicleBody body) {
        this.spec = spec;
        this.body = body;
        this.engine = new Engine(spec.engine());
        this.gearbox = new Gearbox(spec.gearbox());
        for (int i = 0; i < 4; i++) {
            float camberDeg = Math.abs(CarSpec.isFront(i) ? spec.camberFront() : spec.camberRear());
            float[] g = camberGrip(camberDeg);
            camberGripX[i] = g[0];
            camberGripY[i] = g[1];
            TireSpec t = spec.tire(i);
            // 캠버가 클수록 큰 슬립각(깊은 조향·드리프트)에서도 타이어가 버틴다
            tires[i] = new TireSpec(t.grip(), t.peakSlipRatio(), t.peakSlipAngle() * (1 + 0.03f * camberDeg), t.shape(),
                    t.loadSensitivity());
            float side = i % 2 == 1 ? 1 : -1; // 오른쪽 바퀴(1, 3)는 위가 −x 쪽으로
            camberTilt[i] = Quat.axisAngle(Vec3.UNIT_Z, side * (float) Math.toRadians(camberDeg));
            wheels[i] = new WheelState();
            wheels[i].hitDistance = spec.restLength() + spec.wheelRadius();
        }
    }

    /** 틱마다 한 번: 입력 반영 (변속 키 등 "막 눌림" 신호 처리) */
    public void control(DriverInput in) {
        input = in;
        if (in.toggleGearbox()) {
            gearbox.setAutomatic(!gearbox.automatic());
        }
        if (in.cycleTraction()) {
            traction.setMode(traction.mode().next());
            driftengine.core.Log.info("트랙션 컨트롤: " + traction.mode().label());
        }
        if (in.shiftUp()) {
            gearbox.setAutomatic(false);
            int g = gearbox.gear();
            gearbox.shiftTo(g < 0 ? 1 : g + 1);
        }
        if (in.shiftDown()) {
            gearbox.setAutomatic(false);
            int g = gearbox.gear();
            if (g > 1) {
                gearbox.shiftTo(g - 1);
            } else if (g == 1 && Math.abs(forwardSpeed) < 2) {
                gearbox.shiftTo(-1);
            }
        }
    }

    /** 물리 서브스텝 하나 직전에 부른다 */
    public void step(float dt) {
        Vec3 pos = body.position();
        Quat rot = body.rotation();
        Vec3 com = body.centerOfMass();
        Vec3 v = body.linearVelocity();
        Vec3 w = body.angularVelocity();
        Vec3 fwd = rot.rotate(LOCAL_FORWARD);
        Vec3 right = rot.rotate(LOCAL_RIGHT);
        Vec3 up = rot.rotate(LOCAL_UP);
        Vec3 down = up.scale(-1);
        float vF = v.dot(fwd);
        float vR = v.dot(right);
        forwardSpeed = vF;
        speed = v.length();
        updateSlip(dt, vF, vR);

        updatePedals(dt, vF);
        updateSteering(dt, vF, vR);

        // ---------------------------------------------------------------- 서스펜션
        float maxDistance = spec.restLength() + spec.wheelRadius();
        for (int i = 0; i < 4; i++) {
            WheelState ws = wheels[i];
            mounts[i] = pos.add(rot.rotate(spec.mount(i)));
            float hit = body.raycastGround(mounts[i], down, maxDistance);
            if (Float.isNaN(hit)) {
                ws.contact = false;
                ws.compression = 0;
                ws.hitDistance = maxDistance;
                suspension[i] = 0;
                continue;
            }
            Vec3 pointVelocity = v.add(w.cross(mounts[i].sub(com)));
            float x = Suspension.compression(hit, spec.restLength(), spec.wheelRadius());
            suspension[i] = Suspension.force(x, pointVelocity.dot(down), spec.springRate(i), spec.damperRate(i));
            ws.contact = x > 0;
            ws.compression = Math.max(0, x);
            ws.hitDistance = hit;
        }
        // 스태빌라이저: 좌우 눌림 차이만큼 더 눌린 쪽을 밀어 올리고 반대쪽을 덜 민다
        for (int axle = 0; axle < 4; axle += 2) {
            float f = spec.antiRoll(axle) * (wheels[axle].compression - wheels[axle + 1].compression);
            if (wheels[axle].contact) {
                suspension[axle] += f;
            }
            if (wheels[axle + 1].contact) {
                suspension[axle + 1] -= f;
            }
        }
        for (int i = 0; i < 4; i++) {
            WheelState ws = wheels[i];
            ws.load = ws.contact ? Math.min(Math.max(0, suspension[i]), spec.staticLoad(i) * 5) : 0;
            if (ws.load > 0) {
                body.addForce(up.scale(ws.load), mounts[i]);
            }
        }

        // ---------------------------------------------------------------- 엔진 → 변속기
        float ratio = gearbox.totalRatio();
        float drivenOmega = 0;
        int drivenCount = 0;
        for (int i = 0; i < 4; i++) {
            if (spec.driven(i)) {
                drivenOmega += wheels[i].omega;
                drivenCount++;
            }
        }
        drivenOmega /= Math.max(1, drivenCount);
        float wheelRpm = Math.abs(drivenOmega * ratio) * RAD_PER_SEC_TO_RPM;
        // 핸드브레이크를 당기면 클러치를 끊는다 (뒷바퀴가 잠기게, 실제 드리프트 조작과 같음)
        boolean engaged = gearbox.engaged() && !handbrake;
        // 트랙션 컨트롤: 지난 스텝의 구동 바퀴 슬립률로 이번 스텝 엔진 출력을 줄인다
        float spin = 0;
        float peak = 0.1f;
        for (int i = 0; i < 4; i++) {
            if (spec.driven(i) && wheels[i].load > 0) {
                float k = gearbox.gear() < 0 ? -wheels[i].slipRatio : wheels[i].slipRatio;
                spin = Math.max(spin, k);
                peak = spec.tire(i).peakSlipRatio();
            }
        }
        float tcCut = traction.update(dt, spin, peak, driftAngle, engaged && throttle > 0.05f);
        float crank = engine.update(dt, wheelRpm, throttle * tcCut, engaged);
        // 니트로: 누르고 있고, 가속 중이고, 통에 남아 있으면 토크를 더한다. 안 쓸 때는 천천히(드리프트 중엔 빨리) 찬다
        nitroOn = input.nitro() && spec.nitroCapacity() > 0 && nitroTank > 0 && throttle > 0.3f && engaged;
        if (nitroOn) {
            crank *= 1 + spec.nitroBoost();
            nitroTank = Math.max(0, nitroTank - dt / spec.nitroCapacity());
        } else if (spec.nitroCapacity() > 0) {
            boolean drifting = driftAngle > Math.toRadians(15) && speed > 8;
            nitroTank = Math.min(1, nitroTank + dt / (drifting ? 8f : 40f));
        }
        float wheelTorque = crank * ratio * spec.gearbox().efficiency();
        float reflected = engaged && engine.clutchLocked()
                ? spec.engine().inertia() * ratio * ratio / spec.drivenWheelCount() : 0;

        // ---------------------------------------------------------------- 타이어
        Quat steerRot = Quat.axisAngle(up, steer);
        Vec3 frontForward = steerRot.rotate(fwd);
        float r = spec.wheelRadius();
        for (int i = 0; i < 4; i++) {
            WheelState ws = wheels[i];
            boolean front = CarSpec.isFront(i);
            float inertia = spec.wheelInertia() + (spec.driven(i) ? reflected : 0);
            float driveTorque = spec.driven(i) ? wheelTorque * spec.axleTorqueShare(front) / 2 : 0;
            float slope = 0;
            if (ws.load > 0) {
                Vec3 wf = front ? frontForward : fwd;
                Vec3 wr = wf.cross(up);
                Vec3 contact = mounts[i].add(down.scale(ws.hitDistance));
                Vec3 cv = v.add(w.cross(contact.sub(com)));
                float vx = cv.dot(wf);
                float vy = cv.dot(wr);
                float denom = Math.max(Math.abs(vx), VMIN_LONG);
                float kappa = (ws.omega * r - vx) / denom;
                float alpha = (float) Math.atan2(vy, Math.max(Math.abs(vx), VMIN_LAT));
                TireSpec tire = tires[i];
                Tire.force(tire, ws.load, spec.staticLoad(i), kappa, alpha, tireOut);
                Tire.force(tire, ws.load, spec.staticLoad(i), kappa + 0.02f, alpha, tireOut2);
                slope = (tireOut2[0] - tireOut[0]) / 0.02f * r / denom; // dFx/dω
                ws.slipRatio = kappa;
                ws.slipAngle = alpha;
                tireOut[0] *= camberGripX[i];
                tireOut[1] *= camberGripY[i];
                slope *= camberGripX[i];
                ws.fx = tireOut[0];
                ws.fy = tireOut[1];
                float sx = ws.omega * r - vx;
                ws.slideSpeed = (float) Math.sqrt(sx * sx + vy * vy);
                ws.contactPoint = contact;
                contactVx[i] = vx;
                body.addForce(wf.scale(ws.fx).add(wr.scale(ws.fy)), contact);
            } else {
                ws.slipRatio = 0;
                ws.slipAngle = 0;
                ws.fx = 0;
                ws.fy = 0;
                ws.slideSpeed = 0;
                contactVx[i] = 0;
            }
            float eff = inertia + dt * r * Math.max(0, slope);
            effInertia[i] = eff;
            newOmega[i] = ws.omega + dt * (driveTorque - r * ws.fx) / eff;
        }

        // ---------------------------------------------------------------- LSD
        for (int axle = 0; axle < 4; axle += 2) {
            if (!spec.driven(axle)) {
                continue;
            }
            float axleTorque = wheelTorque * spec.axleTorqueShare(axle == 0);
            pair[0] = newOmega[axle];
            pair[1] = newOmega[axle + 1];
            pairInertia[0] = effInertia[axle];
            pairInertia[1] = effInertia[axle + 1];
            Differential.couple(pair, pairInertia, Differential.limit(spec.lsdPreload(), spec.lsdLock(), axleTorque), dt);
            newOmega[axle] = pair[0];
            newOmega[axle + 1] = pair[1];
        }

        // ---------------------------------------------------------------- 브레이크 + 구름 저항 (0 을 넘어 거꾸로 돌리지 않음)
        for (int i = 0; i < 4; i++) {
            WheelState ws = wheels[i];
            boolean front = CarSpec.isFront(i);
            float brakeTorque = brake * spec.brakeTorque() * (front ? spec.brakeFrontBias() : 1 - spec.brakeFrontBias()) / 2;
            if (spec.abs() && brakeTorque > 0 && ws.load > 0 && Math.abs(contactVx[i]) > 3
                    && ws.slipRatio < -1.3f * spec.tire(i).peakSlipRatio()) {
                brakeTorque *= 0.2f; // ABS: 잠기기 시작하면 잠깐 풀어준다
            }
            if (!front && handbrake) {
                brakeTorque += spec.handbrakeTorque();
            }
            brakeTorque += ROLLING_RESISTANCE * ws.load * r;
            float omega = newOmega[i];
            float drop = brakeTorque * dt / effInertia[i];
            omega = Math.abs(omega) <= drop ? 0 : omega - Math.signum(omega) * drop;
            ws.omega = omega;
            ws.spin = (float) ((ws.spin + omega * dt) % (2 * Math.PI));
        }

        // ---------------------------------------------------------------- 드리프트 회전 보조 (각이 한계를 넘을 것 같으면 되돌림)
        // 방향 전환 보조: 드리프트 중에 카운터 쪽(새 방향)으로 핸들을 누르면 차를 진행 방향으로 돌려 반대쪽 드리프트로 넘긴다
        float excess = Math.abs(slipPredicted) - (spec.assistMaxAngle() - (float) Math.toRadians(10));
        boolean transition = transitioning(vF);
        if (spec.assistYaw() > 0 && (excess > 0 || transition) && vF > 1) {
            Vec3 h = spec.halfExtents();
            float yawInertia = spec.mass() * (4 * h.x() * h.x() + 4 * h.z() * h.z()) / 12;
            float strength = Math.max(0, Math.min(excess, 0.5f)) * 20f
                    + (transition ? Math.min(Math.abs(slip), 0.6f) * 5f : 0);
            float torque = Math.signum(slip) * spec.assistYaw() * yawInertia * strength;
            // 앞·뒤 1m 지점에 옆으로 반대 힘 한 쌍 = 위쪽 축 회전력 (앞 힘 × 2m)
            body.addForce(right.scale(-torque / 2), pos.add(fwd));
            body.addForce(right.scale(torque / 2), pos.sub(fwd));
        }

        // ---------------------------------------------------------------- 공기
        if (spec.dragArea() > 0 && speed > 0.1f) {
            float k = 0.5f * CarSpec.AIR_DENSITY * spec.dragArea() * speed;
            body.addForce(v.scale(-k), com);
        }
        if (spec.downforceArea() > 0) {
            float total = 0.5f * CarSpec.AIR_DENSITY * spec.downforceArea() * vF * vF;
            Vec3 frontPoint = pos.add(rot.rotate(new Vec3(0, spec.mountY(), spec.frontAxleZ())));
            Vec3 rearPoint = pos.add(rot.rotate(new Vec3(0, spec.mountY(), spec.rearAxleZ())));
            body.addForce(down.scale(total * spec.downforceFront()), frontPoint);
            body.addForce(down.scale(total * (1 - spec.downforceFront())), rearPoint);
        }

        // ---------------------------------------------------------------- 자동 변속
        EngineSpec e = spec.engine();
        float upRpm = e.redlineRpm() * 0.96f;
        // 올림 판단은 땅 속도 기준 회전수로 (휠스핀으로 회전수만 높을 때 올리면 다음 기어에서 회전수가 너무 낮아짐)
        float groundRpm = Math.abs(vF / spec.wheelRadius() * ratio) * RAD_PER_SEC_TO_RPM;
        float upCheck = Math.min(wheelRpm, groundRpm * 1.15f);
        float downCheck = Math.max(wheelRpm, groundRpm);
        if (handbrake || driftAngle > Math.toRadians(20)) {
            downCheck = Float.MAX_VALUE; // 핸드브레이크·드리프트 중에는 내리지 않는다 (바퀴 회전이 실제 속도와 달라서)
        }
        // 가속 중에는 많이 떨어져야 내린다 (휠스핀 → 올림 → 다시 내림 반복 방지)
        float downRpm = e.idleRpm() + (e.redlineRpm() - e.idleRpm()) * (throttle > 0.5f ? 0.2f : 0.3f);
        gearbox.update(dt, upCheck, downCheck, upRpm, downRpm);

    }

    private void updateSlip(float dt, float vF, float vR) {
        float beta = speed > 3 ? (float) Math.atan2(-vR, vF) : 0;
        float rate = dt > 0 ? (beta - slip) / dt : 0;
        if (Math.abs(beta - slip) > Math.PI) {
            rate = 0; // ±180° 넘어감
        }
        slipRate += (rate - slipRate) * Math.min(1, dt * 20);
        slip = beta;
        slipPredicted = beta + 0.25f * slipRate;
        driftAngle = Math.abs(beta);
    }

    /** 페달: 키보드 누름을 서서히 올리고 내린다. 멈춘 상태에서 브레이크를 누르고 있으면 후진, 후진 중 가속 키면 전진 */
    private void updatePedals(float dt, float vF) {
        boolean reverse = gearbox.gear() < 0;
        boolean slow = Math.abs(vF) < 0.8f;
        if (!reverse && input.brake() && !input.throttle() && slow) {
            standTimer += dt;
            if (standTimer > 0.3f) {
                gearbox.shiftTo(-1);
                standTimer = 0;
            }
        } else if (reverse && input.throttle() && !input.brake() && slow) {
            standTimer += dt;
            if (standTimer > 0.15f) {
                gearbox.shiftTo(1);
                standTimer = 0;
            }
        } else {
            standTimer = 0;
        }
        reverse = gearbox.gear() < 0;
        boolean gas = reverse ? input.brake() : input.throttle();
        boolean stop = reverse ? input.throttle() : input.brake();
        // 드리프트 보조: 각이 한계를 넘으면 스로틀을 줄인다 (키보드는 스로틀을 반만 밟을 수 없으므로)
        float over = Math.abs(slipPredicted) - spec.assistMaxAngle();
        float limit = over > 0 ? Math.max(0.15f, 1 - over / (float) Math.toRadians(15)) : 1;
        if (transitioning(vF)) {
            limit = Math.min(limit, 0.35f); // 방향 전환 중: 뒷바퀴가 다시 붙잡게 스로틀을 살짝 뺀다 (사람이 하는 페인트 동작)
        }
        throttle = approach(throttle, gas ? limit : 0, gas ? 6 : 10, dt);
        brake = approach(brake, stop ? 1 : 0, stop ? 8 : 12, dt);
        handbrake = input.handbrake();
    }

    /**
     * 조향: 입력 각도(속도가 빠를수록 줄임) + 카운터 보조(앞바퀴를 진행 방향 쪽으로).
     * β = 차가 보는 방향과 실제 움직이는 방향 사이 각 (왼쪽 +). 보조 = assist × (0.25초 뒤 예상 β).
     */
    private void updateSteering(float dt, float vF, float vR) {
        float in = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        float beta = vF > 1 ? slipPredicted : 0;
        // 키보드 입력 최대각: 그 속도에서 타이어가 낼 수 있는 선회(L·μg/v²) + 앞 타이어 최대 슬립각의 1/4.
        // 저속은 끝까지 꺾이고, 고속에서는 작게 꺾여 차선 변경에 차가 돌지 않는다
        float v2 = Math.max(1, speed * speed);
        float grip = spec.frontTire().grip() * CarSpec.GRAVITY;
        float inputMax = Math.min(spec.maxSteer(), spec.wheelbase() * grip / v2 + 0.25f * spec.frontTire().peakSlipAngle());
        float target = spec.countersteerAssist() * beta + in * inputMax;
        target = Math.max(-spec.maxSteer(), Math.min(spec.maxSteer(), target));
        float maxDelta = spec.steerRate() / (1 + speed / 25f) * dt; // 빠를수록 천천히 돌린다
        steer += Math.max(-maxDelta, Math.min(maxDelta, target - steer));
    }

    /** 드리프트 중(|β| > 12°)이고 카운터 방향(= 새 방향)으로 핸들을 누르고 있음 */
    private boolean transitioning(float vF) {
        float in = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        return vF > 2 && in != 0 && Math.abs(slip) > Math.toRadians(12) && Math.signum(in) == Math.signum(slip);
    }

    private static float approach(float value, float target, float rate, float dt) {
        float d = target - value;
        float max = rate * dt;
        return value + Math.max(-max, Math.min(max, d));
    }

    /** 차를 바로 세워 옮긴다 (속도 0, 1단) */
    public void reset(Vec3 position, Quat rotation) {
        body.teleport(position, rotation);
        for (WheelState ws : wheels) {
            ws.omega = 0;
            ws.contact = false;
            ws.load = 0;
            ws.hitDistance = spec.restLength() + spec.wheelRadius();
        }
        engine.reset();
        gearbox.reset();
        throttle = 0;
        brake = 0;
        steer = 0;
        standTimer = 0;
    }

    // ------------------------------------------------------------------ 상태

    public Vec3 position() {
        return body.position();
    }

    public Quat rotation() {
        return body.rotation();
    }

    public Vec3 velocity() {
        return body.linearVelocity();
    }

    /** 바퀴 중심의 차체 로컬 위치 (서스펜션 길이 반영) */
    public Vec3 wheelLocalCenter(int i) {
        Vec3 mount = spec.mount(i);
        float travel = Math.min(wheels[i].hitDistance, spec.restLength() + spec.wheelRadius()) - spec.wheelRadius();
        return mount.add(new Vec3(0, -travel, 0));
    }

    /** 바퀴 방향 (차체 로컬): 앞바퀴는 조향, 모두 회전 */
    public Quat wheelLocalRotation(int i) {
        Quat spin = Quat.axisAngle(Vec3.UNIT_X, -wheels[i].spin);
        Quat tilted = camberTilt[i].mul(spin);
        return CarSpec.isFront(i) ? Quat.axisAngle(Vec3.UNIT_Y, steer).mul(tilted) : tilted;
    }

    /**
     * 캠버(도, 절댓값) → {앞뒤 그립 배율, 옆 그립 배율}.
     * 옆: 4° 근처에서 가장 좋고(+5%) 8° 를 넘으면 오히려 손해. 앞뒤: 접지면이 줄어 1° 마다 −1.2%.
     */
    public static float[] camberGrip(float camberDeg) {
        float c = Math.abs(camberDeg);
        float g = c / 4f * (2 - c / 4f);
        return new float[]{1 - 0.012f * c, 1 + 0.05f * Math.max(-0.5f, g)};
    }

    /** 니트로 통 잔량 0~1 */
    public float nitroLevel() {
        return nitroTank;
    }

    /** 이번 스텝에 니트로를 뿜는 중 */
    public boolean nitroActive() {
        return nitroOn;
    }

    /** 조향만 (캘리퍼처럼 바퀴와 같이 꺾이지만 돌지 않는 부품) */
    public Quat wheelLocalSteer(int i) {
        return CarSpec.isFront(i) ? Quat.axisAngle(Vec3.UNIT_Y, steer).mul(camberTilt[i]) : camberTilt[i];
    }

    public TractionControl traction() {
        return traction;
    }

    public WheelState wheel(int i) {
        return wheels[i];
    }

    public CarSpec spec() {
        return spec;
    }

    public VehicleBody body() {
        return body;
    }

    public Engine engine() {
        return engine;
    }

    public Gearbox gearbox() {
        return gearbox;
    }

    /** 앞 방향 속도 (m/s, 후진이면 음수) */
    public float forwardSpeed() {
        return forwardSpeed;
    }

    public float speedKmh() {
        return speed * 3.6f;
    }

    /** 미끄러지는 각 (rad, 0 이상): 차가 보는 방향과 움직이는 방향 차이 */
    public float driftAngle() {
        return driftAngle;
    }

    public float steerAngle() {
        return steer;
    }

    public float throttle() {
        return throttle;
    }

    public float brake() {
        return brake;
    }
}
