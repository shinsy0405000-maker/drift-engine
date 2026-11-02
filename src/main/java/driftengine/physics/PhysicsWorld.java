package driftengine.physics;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyFilter;
import com.github.stephengold.joltjni.BroadPhaseLayerFilter;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.OffsetCenterOfMassShapeSettings;
import com.github.stephengold.joltjni.SpecifiedObjectLayerFilter;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.HeightFieldShapeSettings;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RRayCast;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.RayCastResult;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.TempAllocatorMalloc;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import driftengine.core.EngineException;
import driftengine.core.Log;
import driftengine.world.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Jolt 물리 세계 하나. 엔진의 나머지 코드는 Jolt 클래스를 직접 쓰지 않고 이 클래스만 쓴다
 * (물리 엔진을 바꾸거나 테스트할 때 영향 범위를 줄이려고).
 * <p>
 * 충돌 층(layer) 3개: 0 = 움직이지 않는 것(지형), 1 = 움직이는 것, 2 = 차량 차체.
 * 움직이지 않는 것끼리는 충돌 검사 안 함. 차량 층을 따로 둔 이유: 바퀴 광선이 자기 차체에 맞지 않게 하려고
 * (광선은 지형 층만 본다).
 * 좌표·단위: 미터, 초, 킬로그램. +Y 가 위. 중력 기본값 (0, -9.81, 0).
 */
public final class PhysicsWorld implements AutoCloseable {

    public static final int LAYER_STATIC = 0;
    public static final int LAYER_MOVING = 1;
    public static final int LAYER_VEHICLE = 2;
    private static final int NUM_LAYERS = 3;
    private static final int NUM_BROAD_PHASE_LAYERS = 2; // 정적 / 움직임

    // 세계 크기 상한 (Jolt 는 처음에 정해야 한다). 차량 게임 초반 단계에 충분한 값
    private static final int MAX_BODIES = 4096;
    private static final int NUM_BODY_MUTEXES = 0; // 0 = 기본값
    private static final int MAX_BODY_PAIRS = 8192;
    private static final int MAX_CONTACTS = 8192;

    private final TempAllocatorMalloc tempAllocator;
    private final JobSystemThreadPool jobSystem;
    private final ObjectLayerPairFilterTable layerPairs;
    private final BroadPhaseLayerInterfaceTable broadPhaseLayers;
    private final ObjectVsBroadPhaseLayerFilterTable layerVsBroadPhase;
    private final PhysicsSystem system;
    private final BodyInterface bodies;
    // 모양은 참조 카운트로 관리된다. 몸체가 쓰는 동안 Java 쪽 참조도 들고 있어 회수되지 않게 한다
    private final List<Object> keepAlive = new ArrayList<>();
    // 광선 필터 (매번 만들지 않도록 한 번만)
    private final SpecifiedObjectLayerFilter staticOnly;
    private final BroadPhaseLayerFilter anyBroadPhase;
    private final BodyFilter anyBody;
    private boolean closed;

    public PhysicsWorld() {
        JoltNative.initialize();
        tempAllocator = new TempAllocatorMalloc();
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        jobSystem = new JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, threads);

        layerPairs = new ObjectLayerPairFilterTable(NUM_LAYERS);
        layerPairs.enableCollision(LAYER_MOVING, LAYER_MOVING);
        layerPairs.enableCollision(LAYER_MOVING, LAYER_STATIC);
        layerPairs.disableCollision(LAYER_STATIC, LAYER_STATIC);
        layerPairs.enableCollision(LAYER_VEHICLE, LAYER_STATIC);
        layerPairs.enableCollision(LAYER_VEHICLE, LAYER_MOVING);
        layerPairs.enableCollision(LAYER_VEHICLE, LAYER_VEHICLE);

        // 물체 층 → 넓은 단계(broad phase) 층: 정적은 0, 움직이는 것(차량 포함)은 1
        broadPhaseLayers = new BroadPhaseLayerInterfaceTable(NUM_LAYERS, NUM_BROAD_PHASE_LAYERS);
        broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_STATIC, 0);
        broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_MOVING, 1);
        broadPhaseLayers.mapObjectToBroadPhaseLayer(LAYER_VEHICLE, 1);
        layerVsBroadPhase = new ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, NUM_BROAD_PHASE_LAYERS,
                layerPairs, NUM_LAYERS);
        staticOnly = new SpecifiedObjectLayerFilter(LAYER_STATIC);
        anyBroadPhase = new BroadPhaseLayerFilter();
        anyBody = new BodyFilter();

        system = new PhysicsSystem();
        system.init(MAX_BODIES, NUM_BODY_MUTEXES, MAX_BODY_PAIRS, MAX_CONTACTS,
                broadPhaseLayers, layerVsBroadPhase, layerPairs);
        system.setGravity(0f, -9.81f, 0f);
        bodies = system.getBodyInterface();
        Log.info("물리 세계 생성 (작업 스레드 " + threads + "개, 최대 물체 " + MAX_BODIES + ")");
    }

    /** 지형을 움직이지 않는 높이맵 충돌체로 추가한다. @return 몸체 id */
    public int addTerrain(Terrain terrain, float friction) {
        HeightFieldShapeSettings settings = new HeightFieldShapeSettings(terrain.heights(),
                new Vec3(terrain.originX(), 0f, terrain.originZ()),
                new Vec3(terrain.spacing(), 1f, terrain.spacing()),
                terrain.samples());
        settings.setBitsPerSample(8); // 블록별 8비트 양자화 (Jolt 허용 범위 안의 값)
        ShapeResult result = settings.create();
        if (result.hasError()) {
            throw new EngineException("지형 충돌체 생성 실패: " + result.getError());
        }
        ShapeRefC shape = result.get();
        keepAlive.add(settings);
        keepAlive.add(shape);
        BodyCreationSettings body = new BodyCreationSettings(shape, new RVec3(0, 0, 0), Quat.sIdentity(),
                EMotionType.Static, LAYER_STATIC);
        body.setFriction(friction);
        keepAlive.add(body);
        int id = bodies.createAndAddBody(body, EActivation.DontActivate);
        system.optimizeBroadPhase(); // 큰 정적 물체를 넣은 뒤 한 번 (Jolt 권장)
        return id;
    }

    /** 정적 물체를 많이 넣은 뒤 한 번 (넓은 단계 정리, Jolt 권장) */
    public void optimize() {
        system.optimizeBroadPhase();
    }

    /** 움직이지 않는 상자 (건물 벽 등). yaw = Y 축 회전 (라디안). @return 몸체 id */
    public int addStaticBox(float x, float y, float z, float halfX, float halfY, float halfZ, float yaw, float friction) {
        BoxShape shape = new BoxShape(halfX, halfY, halfZ);
        ShapeRefC ref = shape.toRefC();
        keepAlive.add(shape);
        keepAlive.add(ref);
        BodyCreationSettings body = new BodyCreationSettings(ref, new RVec3(x, y, z), Quat.sRotation(Vec3.sAxisY(), yaw),
                EMotionType.Static, LAYER_STATIC);
        body.setFriction(friction);
        keepAlive.add(body);
        return bodies.createAndAddBody(body, EActivation.DontActivate);
    }

    /** 움직이는 상자를 추가한다. halfExtents 는 각 축 길이의 절반. @return 몸체 id */
    public int addBox(float x, float y, float z, float halfX, float halfY, float halfZ, float friction) {
        BoxShape shape = new BoxShape(halfX, halfY, halfZ);
        ShapeRefC ref = shape.toRefC();
        keepAlive.add(shape);
        keepAlive.add(ref);
        BodyCreationSettings body = new BodyCreationSettings(ref, new RVec3(x, y, z), Quat.sIdentity(),
                EMotionType.Dynamic, LAYER_MOVING);
        body.setFriction(friction);
        keepAlive.add(body);
        return bodies.createAndAddBody(body, EActivation.Activate);
    }

    /**
     * 차체 상자를 추가한다. 무게중심을 상자 중심에서 comOffsetY 만큼 옮기고(낮추기), 질량은 mass 로,
     * 관성은 모양에서 계산(CalculateInertia)한다. @return 몸체 id
     */
    public int addVehicleBody(float x, float y, float z, float halfX, float halfY, float halfZ,
                              float comOffsetY, float mass, float friction) {
        BoxShape box = new BoxShape(halfX, halfY, halfZ);
        keepAlive.add(box);
        OffsetCenterOfMassShapeSettings offset = new OffsetCenterOfMassShapeSettings(new Vec3(0f, comOffsetY, 0f), box);
        keepAlive.add(offset);
        ShapeResult result = offset.create();
        if (result.hasError()) {
            throw new EngineException("차체 모양 생성 실패: " + result.getError());
        }
        ShapeRefC shape = result.get();
        keepAlive.add(shape);
        BodyCreationSettings body = new BodyCreationSettings(shape, new RVec3(x, y, z), Quat.sIdentity(),
                EMotionType.Dynamic, LAYER_VEHICLE);
        MassProperties mp = new MassProperties();
        mp.setMass(mass);
        body.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);
        body.setMassPropertiesOverride(mp);
        body.setFriction(friction);
        body.setAllowSleeping(false); // 서스펜션이 매번 힘을 주므로 잠들면 안 됨
        // 연속 충돌: 빠르게 부딪혀도 얇은 기둥·지형(두께 없음)을 뚫고 지나가지 않게
        body.setMotionQuality(com.github.stephengold.joltjni.enumerate.EMotionQuality.LinearCast);
        // Jolt 기본 감쇠(선 0.05)는 v 에 비례하는 저항이라 시속 160km 에서 2800N 이나 된다 → 공기저항은 Car 가 계산하므로 끈다
        body.setLinearDamping(0f);
        body.setAngularDamping(0.02f);
        keepAlive.add(mp);
        keepAlive.add(body);
        return bodies.createAndAddBody(body, EActivation.Activate);
    }

    /** 월드 점 (px, py, pz) 에 힘 (fx, fy, fz) N 을 다음 step 동안 가한다 (step 이 끝나면 사라짐) */
    public void addForceAt(int body, float fx, float fy, float fz, float px, float py, float pz) {
        bodies.addForce(body, fx, fy, fz, px, py, pz);
    }

    /** 몸체 무게중심 위치 → {x, y, z} */
    public float[] centerOfMass(int body) {
        RVec3 p = bodies.getCenterOfMassPosition(body);
        return new float[]{p.x(), p.y(), p.z()};
    }

    /** 선속도 → {x, y, z} m/s */
    public float[] linearVelocity(int body) {
        Vec3 v = bodies.getLinearVelocity(body);
        return new float[]{v.getX(), v.getY(), v.getZ()};
    }

    /** 각속도 → {x, y, z} rad/s (월드 좌표) */
    public float[] angularVelocity(int body) {
        Vec3 w = bodies.getAngularVelocity(body);
        return new float[]{w.getX(), w.getY(), w.getZ()};
    }

    /**
     * (ox, oy, oz) 에서 방향 (dx, dy, dz) 로 length 만큼, 지형(정적 층)만 보는 광선.
     * @return 닿은 거리 (m). 안 닿으면 NaN
     */
    public float raycastStatic(float ox, float oy, float oz, float dx, float dy, float dz, float length) {
        RRayCast ray = new RRayCast(ox, oy, oz, dx * length, dy * length, dz * length);
        RayCastResult hit = new RayCastResult();
        boolean found = system.getNarrowPhaseQuery().castRay(ray, hit, anyBroadPhase, staticOnly, anyBody);
        return found ? hit.getFraction() * length : Float.NaN;
    }

    /** 몸체 위치·방향을 바꾸고 속도를 0 으로 (차 되돌리기) */
    public void teleport(int body, float x, float y, float z, float qx, float qy, float qz, float qw) {
        bodies.setPositionAndRotation(body, new RVec3(x, y, z), new Quat(qx, qy, qz, qw), EActivation.Activate);
        bodies.setLinearAndAngularVelocity(body, new Vec3(0f, 0f, 0f), new Vec3(0f, 0f, 0f));
    }

    /** 몸체를 세계에서 빼고 지운다 (차 바꾸기) */
    public void removeBody(int body) {
        bodies.removeBody(body);
        bodies.destroyBody(body);
    }

    /** dt 초 진행. collisionSteps: 한 번에 몇 번 나눠 충돌을 풀지 (dt 가 크면 늘린다) */
    public void step(float dt, int collisionSteps) {
        int error = system.update(dt, collisionSteps, tempAllocator, jobSystem);
        if (error != 0) {
            Log.warn("Jolt PhysicsSystem.update 오류 코드 " + error);
        }
    }

    /** 몸체 위치 (원점 = 몸체 좌표계 원점, 상자는 중심) → {x, y, z} */
    public float[] position(int body) {
        RVec3 p = bodies.getPosition(body);
        return new float[]{p.x(), p.y(), p.z()};
    }

    /** 몸체 방향 쿼터니언 → {x, y, z, w} */
    public float[] rotation(int body) {
        Quat q = bodies.getRotation(body);
        return new float[]{q.getX(), q.getY(), q.getZ(), q.getW()};
    }

    /** 속력 (m/s) */
    public float speed(int body) {
        Vec3 v = bodies.getLinearVelocity(body);
        return (float) Math.sqrt(v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ());
    }

    public boolean isActive(int body) {
        return bodies.isActive(body);
    }

    /**
     * (x, fromY, z) 에서 아래로 length 만큼 광선을 쏴 처음 닿는 곳의 높이. 안 닿으면 NaN.
     * 바퀴 서스펜션이 같은 방식으로 땅을 찾는다(5b).
     */
    public float raycastDown(float x, float fromY, float z, float length) {
        RRayCast ray = new RRayCast(x, fromY, z, 0f, -length, 0f);
        RayCastResult hit = new RayCastResult();
        boolean found = system.getNarrowPhaseQuery().castRay(ray, hit);
        if (!found) {
            return Float.NaN;
        }
        return fromY - hit.getFraction() * length;
    }

    public int bodyCount() {
        return system.getNumBodies();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        system.destroyAllBodies();
        anyBody.close();
        anyBroadPhase.close();
        staticOnly.close();
        // 만든 순서의 역순으로 바로 해제 (나머지 설정 객체는 cleaner 가 회수)
        system.close();
        layerVsBroadPhase.close();
        broadPhaseLayers.close();
        layerPairs.close();
        jobSystem.close();
        tempAllocator.close();
        keepAlive.clear();
        Log.info("물리 세계 해제");
    }
}
