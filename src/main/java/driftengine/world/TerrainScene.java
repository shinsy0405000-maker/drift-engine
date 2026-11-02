package driftengine.world;

import driftengine.audio.CarAudio;
import driftengine.audio.SoundFrame;
import driftengine.core.Log;
import driftengine.fx.SkidMarks;
import driftengine.fx.TireSmoke;
import driftengine.scene.Effects;
import driftengine.math.Quat;
import driftengine.math.Vec3;
import driftengine.physics.PhysicsWorld;
import driftengine.scene.Scene;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.MeshData;
import driftengine.scene.mesh.Primitives;
import driftengine.physics.JoltVehicleBody;
import driftengine.vehicle.Car;
import driftengine.vehicle.CarSpec;
import driftengine.game.DriftScorer;
import driftengine.game.GameSession;
import driftengine.scene.Environment;
import driftengine.vehicle.visual.CarModels;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarStyle;
import driftengine.vehicle.DriverInput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 지형 + Jolt 물리 장면.
 * <ul>
 *   <li>5a: 높이맵 지형, 떨어지는 상자 → 시작 시 지형 높이 비교, 5초 뒤 상자 정지 확인</li>
 *   <li>5b: 차체 + 광선 서스펜션 4개 → 4초 뒤 정지 높이(계산값과 비교), 출렁임 횟수, 물리 계산 시간</li>
 *   <li>덩어리 A: 타이어·엔진·터보·변속기·LSD·드리프트 보조, 키보드 운전, cars 폴더의 차 여러 대 (N 키로 바꾸기)</li>
 * </ul>
 * 물리는 고정 틱(60Hz) 하나를 SUBSTEPS 번으로 나눠 진행하고, 화면에는 마지막 두 틱 사이를 보간해서 그린다.
 */
public final class TerrainScene implements Scene {

    public static final int SAMPLES = 512;        // 511m × 511m, 1m 간격
    public static final float SPACING = 1.0f;
    public static final long SEED = 20261028L;
    public static final float FLAT_RADIUS = 120f; // 가운데 평지 (주행·드리프트 연습장)
    public static final float HILL_HEIGHT = 25f;
    public static final Path CAR_DIR = Path.of("cars");
    /** 틱 하나(1/60초)를 몇 번으로 나눠 계산할지 → 240Hz. 단단한 서스펜션도 발산하지 않게 */
    public static final int SUBSTEPS = 4;
    static final float BOX_HALF = 0.5f;
    static final double BOX_CHECK_SECONDS = 5.0;
    static final double CAR_CHECK_SECONDS = 4.0;
    static final float CAR_DROP = 1.0f;           // 정지 높이보다 1m 위에서 떨어뜨림
    static final Vec3 CAR_SPAWN = new Vec3(0, 0, -14);
    private static final Vec3 ONE = new Vec3(1, 1, 1);

    /** 물리 몸체 하나를 따라 그리는 물체 (이전 틱 / 현재 틱 자세를 보관해 보간) */
    private static final class Tracked {
        final SceneObject object;
        Vec3 localOffset; // 몸체 기준 위치 (차체 위 지붕 등)
        Vec3 scale;
        Vec3 prevPos;
        Vec3 currPos;
        Quat prevRot = Quat.IDENTITY;
        Quat currRot = Quat.IDENTITY;

        Tracked(SceneObject object, Vec3 localOffset, Vec3 scale) {
            this.object = object;
            this.localOffset = localOffset;
            this.scale = scale;
        }

        void capture(Vec3 bodyPos, Quat bodyRot, boolean first) {
            capture(bodyPos, bodyRot, Quat.IDENTITY, first);
        }

        /** localRotation: 몸체 기준 추가 회전 (바퀴 조향·굴림) */
        void capture(Vec3 bodyPos, Quat bodyRot, Quat localRotation, boolean first) {
            Vec3 pos = bodyPos.add(bodyRot.rotate(localOffset));
            bodyRot = bodyRot.mul(localRotation);
            if (first || currPos == null) {
                prevPos = pos;
                prevRot = bodyRot;
            } else {
                prevPos = currPos;
                prevRot = currRot;
            }
            currPos = pos;
            currRot = bodyRot;
        }

        void apply(float alpha) {
            if (currPos == null) {
                return; // 차를 바꾼 직후, 아직 위치를 한 번도 안 받음 (다음 물리 틱에서 받음)
            }
            Vec3 p = prevPos.add(currPos.sub(prevPos).scale(alpha));
            Quat q = Quat.nlerp(prevRot, currRot, alpha);
            object.place(p, q.x(), q.y(), q.z(), q.w(), scale);
        }
    }

    private Terrain terrain;
    private PhysicsWorld physics;
    private final List<SceneObject> objects = new ArrayList<>();
    // ------------------------------------------------------------ 지역 (맵 덩어리 C)
    /** 지금 불러온 지역 */
    private driftengine.world.map.Zone zone = driftengine.world.map.Zone.HUB;
    /** 연습장 지형·샵 (한 번 만들어 두고 돌아올 때 다시 씀) */
    private final Terrain hubTerrain;
    private final List<SceneObject> hubObjects = new ArrayList<>();
    /** 지금 지역의 고정 물체 (연습장이면 hubObjects + 상자) */
    private final List<SceneObject> zoneObjects = new ArrayList<>();
    /** 한 번 만든 지역은 다시 쓴다 (서킷·고갯길·도시) */
    private final java.util.Map<driftengine.world.map.Zone, driftengine.world.map.ZoneMap> maps =
            new java.util.EnumMap<>(driftengine.world.map.Zone.class);
    private driftengine.world.map.ZoneMap map;
    private ChunkView chunkView;
    /** 차 물체 (지역을 바꿔도 그대로) */
    private final List<SceneObject> carObjects = new ArrayList<>();
    /** 서킷 자유 주행 랩 기록 */
    private LapTimer practice;
    private float fallY = -20;
    private float prevCarX;
    private float prevCarZ;
    private final List<Integer> boxBodies = new ArrayList<>();
    private final List<Tracked> boxTracks = new ArrayList<>();
    private final List<CarCatalog.Entry> catalog;
    /** 돈·보유 차·튜닝 (저장 파일). 가진 차는 튜닝을 적용해서 달린다 */
    private final GameSession session;
    private final DriftScorer scorer = new DriftScorer();
    /** 엔진·터보·타이어 소리 (소리 장치가 없으면 조용히 꺼짐) */
    private final CarAudio audio = CarAudio.start();
    private final float volume = CarAudio.defaultVolume();
    /** 스키드 자국·타이어 연기 → 렌더러가 읽는 효과 목록 */
    private final SkidMarks skidMarks = new SkidMarks();
    private final TireSmoke smoke = new TireSmoke();
    private final Effects effects = new Effects();
    private SkidMarks.Ground groundHeight;
    private Vec3 lastVelocity = Vec3.ZERO;
    /** 지금 차의 차체 + 바퀴 4개 (차를 바꾸면 메시만 바꾼다) */
    private final SceneObject[] carModel = new SceneObject[5];
    /** 유리 (반투명, 차체를 따라감) + 캘리퍼 4개 (조향만 따라감). 모델 파일에 없으면 숨김 */
    private final SceneObject carGlass = new SceneObject("car-glass", Primitives.cube(0.01f, Vec3.ZERO)).setTransparent(true);
    private final SceneObject[] calipers = new SceneObject[4];
    private final Tracked[] caliperTracks = new Tracked[4];
    private CarStyle carStyle;
    private Car car;
    private GameUi ui;
    private final List<Shops.Shop> shops;
    private ShopUi shopUi;
    private driftengine.scene.Camera viewCamera;
    // 샵 궤도 카메라 (부드럽게 따라가는 값)
    private float shopYaw = Float.NaN;
    private float shopDist;
    private float shopHeight;
    private EventRun eventRun;
    private JoltVehicleBody carBody;
    private DriverInput pendingInput = DriverInput.NONE;
    private boolean anyDriving;
    private final List<Tracked> carTracks = new ArrayList<>(); // 차체
    private final Tracked[] wheelTracks = new Tracked[4];
    private boolean boxesReported;
    private boolean carReported;
    private boolean firstCapture = true;

    // 5b 측정
    private boolean carLanded;
    private float lastVerticalVelocity;
    private int bounces;
    private long physicsNanos;
    private long physicsTicks;

    // 주행 기록 (로그용)
    private double launchStart = -1;
    private boolean launchReported;
    private double driftTime;
    private float driftMax;
    private double simTime;

    public TerrainScene() {
        hubTerrain = Terrain.generate(SAMPLES, SPACING, SEED, FLAT_RADIUS, HILL_HEIGHT);
        terrain = hubTerrain;
        groundHeight = terrain::height;
        // 지형은 32칸 조각으로: 화면·그림자 단계 밖 조각은 건너뛴다. 언덕도 해 그림자를 드리운다
        for (MeshData chunk : terrain.toMeshChunks(FLAT_RADIUS - 10, 32)) {
            hubObjects.add(new SceneObject(chunk.name(), chunk));
        }

        shops = Shops.layout((x, z) -> hubTerrain.height(x, z));
        for (Shops.Shop shop : shops) {
            hubObjects.add(new SceneObject("shop-" + shop.kind().name(), Shops.mesh(shop)));
        }

        catalog = CarCatalog.load(CAR_DIR);
        session = new GameSession(catalog, GameSession.SAVE_FILE, GameSession.CONFIG_FILE);
        for (int i = 0; i < carModel.length; i++) {
            carModel[i] = new SceneObject(i == 0 ? "car-body" : "car-wheel" + (i - 1), Primitives.cube(0.01f, Vec3.ZERO));
            carObjects.add(carModel[i]);
        }
        for (int i = 0; i < 4; i++) {
            calipers[i] = new SceneObject("car-caliper" + i, Primitives.cube(0.01f, Vec3.ZERO));
            carObjects.add(calipers[i]);
        }
        carObjects.add(carGlass);
        ui = new GameUi(session, new GameUi.Host() {
            @Override
            public void carChanged() {
                respawnCurrentCar();
            }

            @Override
            public void startEvent(driftengine.game.Club club, driftengine.game.ClubEvent event) {
                beginEvent(club, event);
            }

            @Override
            public Car car() {
                return car;
            }

            @Override
            public DriftScorer scorer() {
                return scorer;
            }

            @Override
            public EventRun eventRun() {
                return eventRun;
            }

            @Override
            public void travelTo(Shops.Kind kind) {
                travelToShop(kind);
            }

            @Override
            public void travelZone(driftengine.world.map.Zone z) {
                travelToZone(z);
            }

            @Override
            public driftengine.world.map.Zone zone() {
                return zone;
            }

            @Override
            public LapTimer practice() {
                return eventRun == null ? practice : null;
            }
        });
        physics = new PhysicsWorld();
        try {
            physics.addTerrain(terrain, 0.9f);
            addShopColliders();
            checkHeightAgreement();
            addBoxes();
            physics.optimize();
            car = addCar();
            zoneObjects.addAll(hubObjects);
            rebuildObjects();
            captureAll();
            Log.info(String.format("지형 장면: %dx%d 격자 (%.0fm), 상자 %d개, 차 목록 %d종, 물리 몸체 %d개, 물리 %dHz",
                    SAMPLES, SAMPLES, terrain.size(), boxBodies.size(), catalog.size(), physics.bodyCount(), 60 * SUBSTEPS));
            for (CarCatalog.Entry e : catalog) {
                Log.info(String.format("    %s | 클래스 %s, %,d CR", e.spec().summary(),
                        session.market().stockClass(e.spec()).label(), session.market().carPrice(e.spec())));
            }
            Log.info("운전: W/↑ 가속, S/↓ 브레이크(멈춘 뒤 계속 누르면 후진), A/D 조향, Space 핸드브레이크, "
                    + "E/Q 변속(수동), G 자동/수동, X 트랙션 컨트롤, T 차 세우기, N 내 다음 차, C 자유 카메라, Tab 메뉴");
        } catch (RuntimeException e) {
            physics.close();
            audio.close();
            throw e;
        }
    }

    private void addShopColliders() {
        for (Shops.Shop shop : shops) {
            for (float[] b : shop.colliders()) {
                physics.addStaticBox(b[0], b[1], b[2], b[3], b[4], b[5], b[6], 0.6f);
            }
        }
    }

    /** 그릴 물체 목록 다시 모으기: 지역 고정 물체 + 지형 조각 + 차 */
    private void rebuildObjects() {
        objects.clear();
        objects.addAll(zoneObjects);
        if (chunkView != null) {
            chunkView.collect(objects);
        }
        objects.addAll(carObjects);
    }

    // ------------------------------------------------------------------ 지역 불러오기

    /** Tab 메뉴 "지역 이동" */
    private void travelToZone(driftengine.world.map.Zone z) {
        if (eventRun != null) {
            ui.toast("이벤트 중에는 이동할 수 없습니다");
            return;
        }
        loadZone(z);
        ui.toast(z.label + " 도착");
    }

    /** 지역 z 를 불러와 그 지역 출발 위치에 차를 세운다 (같은 지역이면 출발 위치로만) */
    private void loadZone(driftengine.world.map.Zone z) {
        long start = System.nanoTime();
        if (z != zone) {
            CarSpec spec = car.spec();
            PhysicsWorld next = new PhysicsWorld();
            PhysicsWorld old = physics;
            try {
                zoneObjects.clear();
                boxBodies.clear();
                boxTracks.clear();
                practice = null;
                if (z == driftengine.world.map.Zone.HUB) {
                    map = null;
                    chunkView = null;
                    terrain = hubTerrain;
                    physics = next;
                    physics.addTerrain(terrain, 0.9f);
                    addShopColliders();
                    addBoxes();
                    zoneObjects.addAll(hubObjects);
                    fallY = -20;
                } else {
                    driftengine.world.map.ZoneMap m = maps.get(z);
                    if (m == null) {
                        long t0 = System.nanoTime();
                        m = driftengine.world.map.Zones.build(z);
                        maps.put(z, m);
                        Log.info(String.format("지역 만들기: %s, %.0f ms", z.label, (System.nanoTime() - t0) / 1e6));
                    }
                    map = m;
                    terrain = m.terrain;
                    physics = next;
                    physics.addTerrain(terrain, 0.9f);
                    for (float[] b : m.boxes) {
                        physics.addStaticBox(b[0], b[1], b[2], b[3], b[4], b[5], b[6], 0.6f);
                    }
                    for (driftengine.world.map.ZoneMap.Piece p : m.pieces) {
                        zoneObjects.add(new SceneObject(p.mesh().name(), p.mesh()).setCastsShadow(p.shadow()));
                    }
                    chunkView = new ChunkView(m);
                    fallY = m.fallHeight();
                    driftengine.world.map.Course lap = m.course(driftengine.world.map.Zones.CIRCUIT_COURSE);
                    practice = lap != null ? new LapTimer(lap) : null;
                }
                physics.optimize();
                checkHeightAgreement();
            } catch (RuntimeException e) {
                next.close();
                physics = old;
                throw e;
            }
            groundHeight = terrain::height;
            zone = z;
            // 새 물리 세계에 차를 다시 만든다 (옛 세계는 닫음)
            driftengine.vehicle.TractionControl.Mode tc = car.traction().mode();
            carBody = JoltVehicleBody.create(physics, spec, new Vec3(0, terrain.height(0, 0) + 5, 0));
            car = new Car(spec, carBody);
            car.traction().setMode(tc);
            old.close();
        }
        float[] at = spawnPoint(z);
        placeAt(at[0], at[1], at[2]);
        if (chunkView != null) {
            chunkView.update(car.position(), Integer.MAX_VALUE);
            chunkView.logState(z.label);
        }
        rebuildObjects();
        Log.info(String.format("지역 → %s (%.0f ms), 물리 몸체 %d개", z.label, (System.nanoTime() - start) / 1e6, physics.bodyCount()));
    }

    /** 지역 출발 위치 {x, z, yaw} */
    private float[] spawnPoint(driftengine.world.map.Zone z) {
        if (z == driftengine.world.map.Zone.HUB) {
            return new float[]{CAR_SPAWN.x(), CAR_SPAWN.z(), 0};
        }
        driftengine.world.map.ZoneMap.Spawn sp = map.spawn;
        return new float[]{sp.x(), sp.z(), sp.yaw()};
    }

    /** 차를 (x, z) 지면 위에 yaw 방향으로 세우고 효과·점수를 비운다 */
    private void placeAt(float x, float z, float yaw) {
        float ground = terrain.height(x, z);
        car.reset(new Vec3(x, ground + car.spec().restHeight() + 0.15f, z), Quat.axisAngle(Vec3.UNIT_Y, yaw));
        skidMarks.breakAll();
        smoke.clear();
        lastVelocity = Vec3.ZERO;
        scorer.clear();
        firstCapture = true;
        captureAll();
        prevCarX = x;
        prevCarZ = z;
    }

    /** 도로가 있는 지역에서 길에서 멀리 벗어났으면 가장 가까운 도로 가운데로 (진행 방향은 지금 차 방향에 가까운 쪽) */
    private boolean snapToRoad() {
        if (map == null || map.roads == null) {
            return false;
        }
        Vec3 p = car.position();
        driftengine.world.map.RoadNet.Hit h = map.roads.nearest(p.x(), p.z(), 400);
        if (h == null || h.distance() < h.road().halfWidth() + 1.5f) {
            return false;
        }
        float[] a = h.road().path().at(h.s());
        Vec3 fwd = car.rotation().rotate(new Vec3(0, 0, -1));
        float dir = fwd.x() * a[2] + fwd.z() * a[3] >= 0 ? 1 : -1;
        placeAt(a[0], a[1], driftengine.world.map.Zones.yawOf(a[2] * dir, a[3] * dir));
        return true;
    }

    private void addBoxes() {
        MeshData orange = Primitives.cube(1, new Vec3(0.90f, 0.45f, 0.05f));
        MeshData blue = Primitives.cube(1, new Vec3(0.10f, 0.30f, 0.85f));
        float[][] spots = {{0, 0}, {3, 1}, {-2, 4}, {10, -6}, {55, 10}, {-60, 30}, {20, 40}, {-80, -30}};
        for (int i = 0; i < spots.length; i++) {
            float x = spots[i][0];
            float z = spots[i][1];
            float y = terrain.height(x, z) + 4 + i % 3 * 2;
            int body = physics.addBox(x, y, z, BOX_HALF, BOX_HALF, BOX_HALF, 0.8f);
            SceneObject obj = new SceneObject("box" + i, i % 2 == 0 ? orange : blue);
            boxBodies.add(body);
            boxTracks.add(new Tracked(obj, Vec3.ZERO, new Vec3(1, 1, 1)));
            zoneObjects.add(obj);
        }
    }

    private Car addCar() {
        CarSpec spec = session.garage().currentSpec();
        float ground = terrain.height(CAR_SPAWN.x(), CAR_SPAWN.z());
        carBody = JoltVehicleBody.create(physics, spec, new Vec3(CAR_SPAWN.x(), ground + spec.restHeight() + CAR_DROP, CAR_SPAWN.z()));
        Car c = new Car(spec, carBody);
        buildCarModel(spec, session.garage().currentStyle());
        audio.setCar(session.garage().currentSound());
        Log.info(String.format("차량: %s | 스프링 앞 %.1f / 뒤 %.1f kN/m, 감쇠비 %.2f, 계산상 정지 높이 %.3fm, 눌림 앞 %.3f / 뒤 %.3fm",
                spec.summary(), spec.springFront() / 1000, spec.springRear() / 1000, spec.dampingRatio(), spec.restHeight(),
                spec.staticSag(0), spec.staticSag(2)));
        return c;
    }

    /** 지금 차(튜닝·바디킷 반영)의 차체·바퀴 메시를 만들어 끼운다. 렌더러가 새 메시를 GPU 에 올린다 */
    private void buildCarModel(CarSpec spec, CarStyle style) {
        long start = System.nanoTime();
        carStyle = style;
        // 모델링한 차 (cars/models). 높이 기준은 순정 정지 높이 → 차고를 낮추면 차체가 내려가 보인다
        float stockRest = session.garage().entry(spec.id()).spec().restHeight();
        carModel[0].setMesh(CarModels.bodyOrBuilt(spec, style, stockRest));
        for (int i = 0; i < 4; i++) {
            carModel[1 + i].setMesh(CarModels.wheelOrBuilt(spec, style, i));
        }
        MeshData glass = CarModels.glass(spec.id(), style, stockRest);
        carGlass.setVisible(glass != null);
        if (glass != null) {
            carGlass.setMesh(glass);
        }
        for (int i = 0; i < 4; i++) {
            MeshData cal = CarModels.caliper(spec.id(), style, CarSpec.isFront(i), i % 2 == 0 ? -1 : 1);
            calipers[i].setVisible(cal != null);
            if (cal != null) {
                calipers[i].setMesh(cal);
            }
        }
        carTracks.clear();
        carTracks.add(new Tracked(carModel[0], Vec3.ZERO, ONE));
        carTracks.add(new Tracked(carGlass, Vec3.ZERO, ONE));
        for (int i = 0; i < 4; i++) {
            wheelTracks[i] = new Tracked(carModel[1 + i], Vec3.ZERO, ONE);
            caliperTracks[i] = new Tracked(calipers[i], Vec3.ZERO, ONE);
        }
        firstCapture = true;
        Log.info(String.format("차 모델: %s (바디킷 %s), %.0f ms", spec.name(), style.kit().text(), (System.nanoTime() - start) / 1e6));
    }

    /** 차를 바꾸거나 튜닝했을 때: 지금 자리에서 새 제원으로 다시 세운다 */
    private void respawnCurrentCar() {
        Vec3 p = car.position();
        Quat yaw = car.rotation().yawOnly();
        carBody.remove();
        CarSpec spec = session.garage().currentSpec();
        float ground = terrain.height(p.x(), p.z());
        Vec3 at = new Vec3(p.x(), ground + spec.restHeight() + 0.3f, p.z());
        carBody = JoltVehicleBody.create(physics, spec, at);
        driftengine.vehicle.TractionControl.Mode tc = car.traction().mode(); // 차를 바꿔도 트랙션 컨트롤 단계는 그대로
        car = new Car(spec, carBody);
        car.traction().setMode(tc);
        car.reset(at, yaw);
        buildCarModel(spec, session.garage().currentStyle());
        audio.setCar(session.garage().currentSound());
        skidMarks.breakAll();
        lastVelocity = Vec3.ZERO;
        launchStart = -1;
        launchReported = false;
        Log.info("차 → " + spec.summary() + " | 성능 " + driftengine.game.PerformanceRating.of(spec)
                + " 클래스 " + session.garage().currentClass().label());
    }

    /** N: 내 차고의 다음 차 */
    private void switchCar() {
        int n = session.profile().cars().size();
        if (n > 1) {
            session.garage().select((session.profile().selectedIndex() + 1) % n);
            respawnCurrentCar();
            ui.toast(car.spec().brand() + " " + car.spec().name());
        } else {
            ui.toast("가진 차가 한 대뿐입니다 (Tab → 딜러쉽)");
        }
    }

    /** 클럽 이벤트 시작: 출발점으로 옮기고 점수를 비운 뒤 카운트다운 */
    private void beginEvent(driftengine.game.Club club, driftengine.game.ClubEvent event) {
        driftengine.world.map.Zone z = driftengine.world.map.Zone.ofTrack(event.track());
        loadZone(z);
        driftengine.world.map.Course course = map != null ? map.course(event.track()) : null;
        if (course == null && map != null && map.eventSpawn != null) {
            placeAt(map.eventSpawn.x(), map.eventSpawn.z(), map.eventSpawn.yaw());
        }
        if (course != null) {
            // 출발선 뒤 (서킷 25m, 고갯길 20m) 도로 가운데, 진행 방향
            float back = course.loop() ? 25 : 20;
            float[] a = course.behindStart(back);
            placeAt(a[0], a[1], driftengine.world.map.Zones.yawOf(a[2], a[3]));
        }
        eventRun = new EventRun(event, club.name(), course);
        Log.info("이벤트 시작: " + club.name() + " / " + event.title() + " (" + z.label + ", 목표 " + event.target() + ")");
    }

    private void updateEvent(float dt) {
        if (eventRun == null) {
            return;
        }
        EventRun.Phase before = eventRun.phase;
        Vec3 p = car.position();
        boolean finished = eventRun.update(dt, prevCarX, prevCarZ, p.x(), p.z());
        if (before == EventRun.Phase.COUNTDOWN && eventRun.phase == EventRun.Phase.RUNNING) {
            scorer.clear(); // 출발 순간부터 센다
        }
        if (finished) {
            if (eventRun.scoreEvent()) {
                eventRun.result = scorer.totalWithChain();
            }
            driftengine.game.Garage.Result r = session.garage().finishEvent(eventRun.event, eventRun.result);
            eventRun.stars = eventRun.event.stars(eventRun.result);
            eventRun.message = eventRun.failed ? "제한 시간 초과 (실패)" : r.message();
            session.save();
            Log.info(eventRun.scoreEvent()
                    ? String.format("이벤트 끝: %s %,d점 → %s", eventRun.event.title(), (long) eventRun.result, r.message())
                    : String.format("이벤트 끝: %s 랩 %.2f 초 → %s", eventRun.event.title(), eventRun.result, eventRun.message));
        }
        if (eventRun.phase == EventRun.Phase.DONE) {
            eventRun = null;
        }
    }

    /** T: 차를 지금 자리에서 바로 세운다 (뒤집혔을 때) */
    private void resetCar() {
        if (snapToRoad()) {
            ui.toast("도로로 돌아왔습니다");
            Log.info("차 세움: 가까운 도로로");
            return;
        }
        Vec3 p = car.position();
        float ground = terrain.height(p.x(), p.z());
        car.reset(new Vec3(p.x(), ground + car.spec().restHeight() + 0.3f, p.z()), car.rotation().yawOnly());
        skidMarks.breakAll();
        firstCapture = true;
        Log.info(String.format("차 세움 (%.1f, %.1f)", p.x(), p.z()));
    }

    /** 무작위(고정 seed) 지점에서 Jolt 광선 높이와 Java 높이를 비교해 로그로 남긴다. */
    private void checkHeightAgreement() {
        java.util.Random random = new java.util.Random(7);
        float maxError = 0;
        int misses = 0;
        int count = 300;
        float half = terrain.size() / 2 - 1;
        for (int i = 0; i < count; i++) {
            float x = (random.nextFloat() * 2 - 1) * half;
            float z = (random.nextFloat() * 2 - 1) * half;
            float expected = terrain.height(x, z);
            if (insideBox(x, z)) {
                continue; // 건물·벽 위는 상자에 닿으므로 지형 비교에서 뺀다
            }
            float hit = physics.raycastDown(x, expected + 50, z, 100);
            if (Float.isNaN(hit)) {
                misses++;
                continue;
            }
            maxError = Math.max(maxError, Math.abs(hit - expected));
        }
        String verdict = misses == 0 && maxError < 0.05f ? "일치" : "불일치 → 확인 필요";
        Log.info(String.format("지형 높이 비교 (Jolt 광선 vs Java): %d 지점, 최대 차이 %.4fm, 놓침 %d → %s",
                count, maxError, misses, verdict));
    }

    /** 가까운 물리 상자 설명 (기록용, 없으면 빈 문자열) */
    private String nearestBoxText(Vec3 p, float range) {
        if (map == null) {
            return "";
        }
        float best = range;
        float[] hit = null;
        for (float[] b : map.boxes) {
            if (b[3] > 100 || b[5] > 100) {
                continue;
            }
            float d = (float) Math.hypot(p.x() - b[0], p.z() - b[2]) - Math.max(b[3], b[5]);
            if (d < best) {
                best = d;
                hit = b;
            }
        }
        if (hit == null) {
            return ", 근처 상자 없음";
        }
        String what = hit[3] < 0.2f ? "가로등" : hit[3] < 0.4f ? "나무" : hit[4] > 3 ? "건물" : "벽·가드레일";
        return String.format(", 근처 %s (%.1f, %.1f)", what, hit[0], hit[2]);
    }

    // 바퀴 아래 땅 높이 점검 (물리 지형이 보이는 지형과 다른지)
    private int groundMismatch;
    private float groundWorst;
    private float groundWorstX;
    private float groundWorstZ;
    private double groundReportAt;

    /** 바퀴가 닿은 점 높이 vs 지형 높이 (상자 위는 빼고). 5cm 넘게 어긋나면 모아서 2초마다 기록 */
    private void checkWheelGround() {
        for (int i = 0; i < 4; i++) {
            Car.WheelState w = car.wheel(i);
            if (!w.contact()) {
                continue;
            }
            Vec3 c = w.contactPoint();
            float d = c.y() - terrain.height(c.x(), c.z());
            if (Math.abs(d) > 0.05f && !insideBox(c.x(), c.z())) {
                groundMismatch++;
                if (Math.abs(d) > Math.abs(groundWorst)) {
                    groundWorst = d;
                    groundWorstX = c.x();
                    groundWorstZ = c.z();
                }
            }
        }
        if (groundMismatch > 0 && simTime >= groundReportAt) {
            Log.warn(String.format("바퀴 아래 땅 높이 어긋남 %d번 (2초), 가장 큰 %+.2fm at (%.1f, %.1f) [%s]",
                    groundMismatch, groundWorst, groundWorstX, groundWorstZ, zone.label));
            groundMismatch = 0;
            groundWorst = 0;
            groundReportAt = simTime + 2;
        }
    }

    private boolean insideBox(float x, float z) {
        if (map == null) {
            return false;
        }
        for (float[] b : map.boxes) {
            float c = (float) Math.cos(b[6]), s = (float) Math.sin(b[6]);
            float dx = x - b[0], dz = z - b[2];
            // 상자 로컬: x 축 = (cos, −sin), z 축 = (sin, cos)
            float lx = dx * c - dz * s, lz = dx * s + dz * c;
            if (Math.abs(lx) < b[3] + 0.5f && Math.abs(lz) < b[5] + 0.5f) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void retainMeshes(java.util.function.Consumer<MeshData> keep) {
        if (chunkView != null) {
            chunkView.forEachCached(keep);
        }
    }

    @Override
    public void beginFrame() {
        if (chunkView != null) {
            Vec3 eye = viewCamera != null ? viewCamera.position() : car.position();
            if (chunkView.update(eye, ChunkView.BUILD_PER_FRAME)) {
                rebuildObjects();
            }
        }
        for (SceneObject object : objects) {
            object.beginFrame();
        }
    }

    @Override
    public void drive(DriverInput input) {
        pendingInput = input;
    }

    @Override
    public void update(double simTimeSeconds, double dtSeconds) {
        simTime = simTimeSeconds;
        DriverInput in = pendingInput;
        pendingInput = DriverInput.NONE;
        if (eventRun != null && eventRun.controlsLocked()) {
            in = DriverInput.of(false, true, false, false, true); // 카운트다운: 제자리에 붙잡아 둠
        }
        if (shopUi != null) {
            in = DriverInput.of(false, true, false, false, true); // 샵 안: 무대 위에 세워 둠
            shopUi.update((float) dtSeconds);
        }
        if (in.nextCar() && eventRun == null) {
            switchCar();
        }
        if (in.resetCar()) {
            resetCar();
        }
        anyDriving |= in.throttle() || in.brake() || in.left() || in.right() || in.handbrake();
        car.control(in);
        long start = System.nanoTime();
        float sub = (float) dtSeconds / SUBSTEPS;
        for (int s = 0; s < SUBSTEPS; s++) {
            car.step(sub);
            physics.step(sub, 1);
        }
        physicsNanos += System.nanoTime() - start;
        physicsTicks++;

        recoverIfSunk();
        checkWheelGround();
        if (car.position().y() < fallY) {
            // 지형 밖으로 떨어짐 → 가까운 도로, 없으면 지역 출발 위치
            if (!snapToRoad()) {
                float[] at = spawnPoint(zone);
                placeAt(at[0], at[1], at[2]);
            }
            Log.info("차가 지형 밖으로 떨어져 되돌림");
        }
        captureAll();
        updateEffects((float) dtSeconds);
        measureCar();
        recordDriving(in, (float) dtSeconds);
        score((float) dtSeconds);
        updateEvent((float) dtSeconds);
        updatePractice((float) dtSeconds);
        Vec3 now = car.position();
        prevCarX = now.x();
        prevCarZ = now.z();
        ui.update((float) dtSeconds);
        // 메뉴가 열려 있으면 소리를 줄인다
        audio.push(SoundFrame.of(car, volume * (ui.open() || shopUi != null ? 0.35f : 1f)));
        abandonTimer = Math.max(0, abandonTimer - (float) dtSeconds);
        if (!carReported && simTimeSeconds >= CAR_CHECK_SECONDS && !anyDriving) {
            carReported = true;
            reportCar();
        }
        if (!boxesReported && simTimeSeconds >= BOX_CHECK_SECONDS) {
            boxesReported = true;
            reportBoxes();
        }
    }

    /**
     * 차체 가운데가 땅보다 아래로 빠졌으면 (두께 없는 지형을 뚫고 내려감) 바로 그 자리 땅 위로 올린다.
     * 지형은 한 겹이라(굴·다리 없음) 차가 땅 아래에 있을 수 있는 경우는 없다. 원인을 찾도록 기록을 남긴다.
     */
    private void recoverIfSunk() {
        Vec3 p = car.position();
        float ground = terrain.height(p.x(), p.z());
        if (p.y() > ground - 0.3f) {
            return;
        }
        Vec3 v = car.velocity();
        String near = "";
        if (map != null) {
            for (float[] b : map.boxes) {
                if (b[3] < 100 && b[5] < 100 && Math.abs(p.x() - b[0]) < b[3] + 4 && Math.abs(p.z() - b[2]) < b[5] + 4) {
                    near = String.format(", 가까운 상자 (%.1f, %.1f) 반크기 %.1f×%.1f×%.1f", b[0], b[2], b[3], b[4], b[5]);
                    break;
                }
            }
        }
        Log.warn(String.format("차가 땅 아래로 빠짐 → 다시 올림: 위치 (%.1f, %.2f, %.1f), 땅 %.2f, 속도 (%.1f, %.1f, %.1f) m/s%s",
                p.x(), p.y(), p.z(), ground, v.x(), v.y(), v.z(), near));
        Vec3 f = car.rotation().rotate(new Vec3(0, 0, -1));
        placeAt(p.x(), p.z(), (float) Math.atan2(-f.x(), -f.z()));
    }

    /** 서킷 자유 주행: 출발선을 지나면 랩 시간을 잰다 (이벤트 중에는 멈춤) */
    private void updatePractice(float dt) {
        if (practice == null || eventRun != null || shopUi != null) {
            return;
        }
        Vec3 p = car.position();
        if (practice.update(dt, prevCarX, prevCarZ, p.x(), p.z())) {
            boolean best = practice.last() <= practice.best();
            ui.toast(String.format("랩 %s%s", lapText(practice.last()), best ? "  (최고 기록)" : ""));
            Log.info(String.format("연습 랩 %d: %.2f 초 (최고 %.2f)", practice.laps(), practice.last(), practice.best()));
        }
    }

    static String lapText(float seconds) {
        int m = (int) (seconds / 60);
        return String.format("%d:%06.3f", m, seconds - m * 60);
    }

    /** 바퀴마다 스키드 자국·연기 (미끄러지는 속도·하중으로 세기) */
    private void updateEffects(float dt) {
        Vec3 v = car.velocity();
        for (int i = 0; i < 4; i++) {
            Car.WheelState w = car.wheel(i);
            float load = w.load() / Math.max(1, car.spec().staticLoad(i));
            Vec3 p = w.contactPoint();
            float width = CarSpec.isFront(i) ? carStyle.tireWidthF() : carStyle.tireWidthR();
            skidMarks.update(i, w.contact(), p.x(), p.z(), SkidMarks.intensity(w.slideSpeed(), load), width, groundHeight);
            float puff = w.contact() ? TireSmoke.intensity(w.slideSpeed(), load) : 0;
            smoke.emit(i, p.x(), p.y(), p.z(), v.x(), v.y(), v.z(), puff, dt);
        }
        smoke.update(dt, groundHeight);
        effects.clear();
        skidMarks.write(effects);
        smoke.write(effects);
    }

    @Override
    public Effects effects() {
        return effects;
    }

    private void captureAll() {
        for (int i = 0; i < boxBodies.size(); i++) {
            int body = boxBodies.get(i);
            float[] p = physics.position(body);
            float[] q = physics.rotation(body);
            boxTracks.get(i).capture(new Vec3(p[0], p[1], p[2]), new Quat(q[0], q[1], q[2], q[3]).normalize(), firstCapture);
        }
        Vec3 pos = car.position();
        Quat rot = car.rotation();
        for (Tracked t : carTracks) {
            t.capture(pos, rot, firstCapture);
        }
        for (int i = 0; i < 4; i++) {
            Vec3 center = pos.add(rot.rotate(car.wheelLocalCenter(i)));
            wheelTracks[i].capture(center, rot, car.wheelLocalRotation(i), firstCapture);
            caliperTracks[i].capture(center, rot, car.wheelLocalSteer(i), firstCapture);
        }
        firstCapture = false;
    }

    @Override
    public void interpolate(float alpha) {
        float a = Math.max(0, Math.min(1, alpha));
        for (Tracked t : boxTracks) {
            t.apply(a);
        }
        for (Tracked t : carTracks) {
            t.apply(a);
        }
        for (Tracked t : wheelTracks) {
            t.apply(a);
        }
        for (Tracked t : caliperTracks) {
            t.apply(a);
        }
    }

    /** 0→100km/h 시간(멈춘 상태에서 W 만 계속 눌러 직선 가속했을 때)과 드리프트(15° 넘게 1초 이상)를 로그로 남긴다 */
    private void recordDriving(DriverInput in, float dt) {
        float kmh = car.forwardSpeed() * 3.6f;
        boolean clean = in.throttle() && !in.brake() && !in.handbrake() && car.driftAngle() < Math.toRadians(10);
        if (launchStart >= 0 && !launchReported && !clean) {
            launchStart = -1; // 가속을 떼거나 미끄러지면 측정 취소 (직선 풀 가속만 잰다)
        }
        if (Math.abs(kmh) < 1 && !in.throttle()) {
            launchStart = -1;
            launchReported = false;
        } else if (launchStart < 0 && !launchReported && in.throttle() && Math.abs(kmh) < 1) {
            launchStart = simTime;
        }
        if (launchStart >= 0 && !launchReported && kmh >= 100) {
            launchReported = true;
            Log.info(String.format("0→100km/h: %.2f 초 (%s)", simTime - launchStart, car.spec().name()));
        }
        float angle = (float) Math.toDegrees(car.driftAngle());
        if (angle > 15 && angle < 120 && car.speedKmh() > 20) { // 120° 넘으면 뒤로 가는 중 (스핀)
            driftTime += dt;
            driftMax = Math.max(driftMax, angle);
        } else {
            if (driftTime >= 1.0) {
                Log.info(String.format("드리프트: %.1f 초, 최대 각 %.0f°", driftTime, driftMax));
            }
            driftTime = 0;
            driftMax = 0;
        }
    }

    /** 드리프트 점수: 확정되면 자유 주행 보상(200점당 1 CR), 스핀·충돌이면 잃음 */
    private void score(float dt) {
        Vec3 v = car.velocity();
        Vec3 dv = v.sub(lastVelocity);
        lastVelocity = v;
        DriftScorer.Event e;
        if (new Vec3(dv.x(), 0, dv.z()).length() > 5) {
            e = scorer.crash(); // 한 틱(1/60초)에 수평 속도가 5m/s 넘게 바뀜 = 부딪힘
            Vec3 p = car.position();
            Log.info(String.format("부딪힘: 위치 (%.1f, %.1f), 속도 변화 %.1f m/s%s", p.x(), p.z(),
                    new Vec3(dv.x(), 0, dv.z()).length(), nearestBoxText(p, 6)));
        } else {
            e = scorer.update(dt, car.driftAngle(), car.speedKmh(), car.forwardSpeed() * 3.6f);
        }
        switch (e) {
            case BANKED -> {
                long gain = eventRun == null ? session.garage().rewardFreeDrift(scorer.lastBanked()) : 0;
                ui.banked(scorer.lastBanked());
                Log.info(String.format("드리프트 점수 확정: %,d점 (+%d CR, 총 %,d점, 돈 %,d CR)",
                        scorer.lastBanked(), gain, scorer.total(), session.profile().money()));
            }
            case SPIN -> {
                ui.toast("스핀! 점수를 잃었습니다");
                Log.info("스핀! 쌓던 드리프트 점수를 잃음");
            }
            case CRASH -> {
                ui.toast("충돌! 점수를 잃었습니다");
                Log.info("충돌! 쌓던 드리프트 점수를 잃음");
            }
            default -> {
            }
        }
    }

    @Override
    public void uiInput(driftengine.ui.UiInput input) {
        if (eventRun != null && eventRun.phase != EventRun.Phase.DONE) {
            // 이벤트 중에는 메뉴를 막는다 (중간에 차/튜닝을 바꾸는 반칙 방지). Tab 두 번 = 포기.
            if (input.menu()) {
                if (abandonTimer > 0) {
                    Log.info("이벤트 포기: " + eventRun.event.title());
                    eventRun = null;
                    abandonTimer = 0;
                    scorer.clear();
                    ui.toast("이벤트를 포기했습니다");
                } else {
                    abandonTimer = 3f;
                    ui.toast("이벤트 중입니다. 포기하려면 Tab 을 한 번 더");
                }
            }
            return;
        }
        if (shopUi != null) {
            shopUi.input(input);
            return;
        }
        if (!ui.open() && input.enterShop()) {
            Shops.Shop at = promptShop();
            if (at != null) {
                enterShop(at);
                return;
            }
        }
        ui.input(input);
    }

    // ------------------------------------------------------------------ 샵

    /** 입구 원 안에 천천히 있으면 그 샵 (이벤트 중이면 없음) */
    private Shops.Shop promptShop() {
        if (eventRun != null || shopUi != null || car.speedKmh() > 20 || zone != driftengine.world.map.Zone.HUB) {
            return null;
        }
        for (Shops.Shop shop : shops) {
            if (shop.atEntrance(car.position())) {
                return shop;
            }
        }
        return null;
    }

    private Shops.Shop shop(Shops.Kind kind) {
        for (Shops.Shop s : shops) {
            if (s.kind() == kind) {
                return s;
            }
        }
        throw new IllegalArgumentException(kind.name());
    }

    /** Tab 메뉴에서 "샵으로 이동": 입구 원 바로 앞에 샵을 보고 세운다 */
    private void travelToShop(Shops.Kind kind) {
        if (eventRun != null) {
            ui.toast("이벤트 중에는 이동할 수 없습니다");
            return;
        }
        if (zone != driftengine.world.map.Zone.HUB) {
            loadZone(driftengine.world.map.Zone.HUB);
        }
        Shops.Shop s = shop(kind);
        Vec3 at = s.local(0, 0, Shops.Shop.RING_DISTANCE + 2.5f);
        float ground = terrain.height(at.x(), at.z());
        placeCar(new Vec3(at.x(), ground + car.spec().restHeight() + 0.2f, at.z()), s.yaw()); // 앞이 샵 쪽 (−front)
        ui.toast(kind.label + " 앞입니다. 원 안에서 Enter");
    }

    /** 차를 세운다. towardYaw = 차 앞이 바라볼 방향의 반대(샵 앞 방향) 기준 각 */
    private void placeCar(Vec3 at, float shopYaw) {
        // 샵 앞 방향 f = (sin y, 0, cos y). 차가 샵을 보려면 차 앞(−z 로컬) = −f → 회전 각 = y
        car.reset(at, Quat.axisAngle(Vec3.UNIT_Y, shopYaw));
        skidMarks.breakAll();
        lastVelocity = Vec3.ZERO;
        scorer.clear();
        firstCapture = true;
        captureAll();
        prevCarX = at.x();
        prevCarZ = at.z();
    }

    private void enterShop(Shops.Shop s) {
        float ground = terrain.height(s.center().x(), s.center().z());
        // 무대 가운데, 차 앞이 샵 앞(출발점 쪽)을 보게: 차 앞(−z) = f → 회전 각 = y + π
        car.reset(new Vec3(s.center().x(), ground + car.spec().restHeight() + 0.05f, s.center().z()),
                Quat.axisAngle(Vec3.UNIT_Y, s.yaw() + (float) Math.PI));
        skidMarks.breakAll();
        lastVelocity = Vec3.ZERO;
        scorer.clear();
        firstCapture = true;
        captureAll();
        shopYaw = Float.NaN;
        Log.info("샵 들어감: " + s.kind().label);
        shopUi = new ShopUi(session, s.kind(), new ShopUi.Host() {
            @Override
            public void carChanged() {
                respawnCurrentCar();
            }

            @Override
            public void preview(driftengine.vehicle.visual.CarStyle style) {
                buildCarModel(car.spec(), style);
            }

            @Override
            public void exit() {
                Log.info("샵 나감: " + s.kind().label);
                shopUi = null;
            }

            @Override
            public void toast(String message) {
                ui.toast(message);
            }
        });
    }

    @Override
    public boolean cameraOverride(driftengine.scene.Camera camera, float dt) {
        if (shopUi == null) {
            return false;
        }
        ShopUi.Focus f = shopUi.focus();
        Tracked chassis = carTracks.get(0);
        Vec3 pos = chassis.object.model().transformPoint(Vec3.ZERO);
        Vec3 fwd = chassis.object.model().transformPoint(new Vec3(0, 0, -1)).sub(pos);
        float carYaw = (float) Math.atan2(fwd.x(), fwd.z());
        float target = carYaw + (float) Math.toRadians(f.yawDeg());
        float k = 1 - (float) Math.exp(-4f * Math.max(0, dt));
        if (Float.isNaN(shopYaw)) {
            shopYaw = target;
            shopDist = f.distance();
            shopHeight = f.height();
        } else {
            float d = (float) Math.IEEEremainder(target - shopYaw, 2 * Math.PI);
            shopYaw += d * k;
            shopDist += (f.distance() - shopDist) * k;
            shopHeight += (f.height() - shopHeight) * k;
        }
        Vec3 dir = new Vec3((float) Math.sin(shopYaw), 0, (float) Math.cos(shopYaw));
        Vec3 eye = pos.add(dir.scale(shopDist)).add(new Vec3(0, shopHeight, 0));
        // 차가 화면 오른쪽 가운데(왼쪽 패널 사이)에 오도록 바라보는 점을 차 왼쪽으로 옮긴다
        Vec3 right = new Vec3(-dir.z(), 0, dir.x()); // 카메라 기준 오른쪽 = (차를 보는 방향 × 위)
        Vec3 look = pos.add(new Vec3(0, 0.35f, 0)).add(right.scale(shopDist * 0.17f));
        float[] yp = driftengine.scene.ChaseCamera.yawPitchTowards(eye, look);
        camera.position(eye).rotation(yp[0], yp[1]);
        return true;
    }

    @Override
    public void observeCamera(driftengine.scene.Camera camera) {
        viewCamera = camera;
    }

    /** 샵 위치를 화면 좌표로 (카메라 뒤면 빼기) */
    private List<GameUi.Marker> markers(int width, int height) {
        List<GameUi.Marker> out = new ArrayList<>();
        if (zone != driftengine.world.map.Zone.HUB) {
            return out;
        }
        Shops.Shop prompt = promptShop();
        for (Shops.Shop s : shops) {
            if (s == prompt) {
                out.add(new GameUi.Marker(s.kind(), 0, 0, 0, true));
                continue;
            }
            if (viewCamera == null) {
                continue;
            }
            Vec3 p = s.local(0, 7.5f, 0);
            float[] clip = viewCamera.viewProjection().transform(p.x(), p.y(), p.z(), 1);
            if (clip[3] <= 0.1f) {
                continue;
            }
            float nx = clip[0] / clip[3], ny = clip[1] / clip[3];
            if (Math.abs(nx) > 1.1f || Math.abs(ny) > 1.1f) {
                continue;
            }
            out.add(new GameUi.Marker(s.kind(), (nx * 0.5f + 0.5f) * width, (ny * 0.5f + 0.5f) * height, s.distance(car.position()), false));
        }
        return out;
    }

    /** 이벤트 포기 확인 대기 시간(초). */
    private float abandonTimer;

    @Override
    public boolean menuOpen() {
        return ui.open() || shopUi != null;
    }

    @Override
    public void drawOverlay(driftengine.ui.UiCanvas canvas) {
        if (shopUi != null) {
            shopUi.draw(canvas);
        }
        ui.draw(canvas, markers(canvas.width(), canvas.height()), shopUi != null);
    }

    @Override
    public String statusText() {
        var gb = car.gearbox();
        var e = car.engine();
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(zone.label).append("] ");
        sb.append(car.spec().brand()).append(' ').append(car.spec().name())
                .append(String.format("  %3.0f km/h  %s단%s  %5.0f rpm", Math.abs(car.forwardSpeed()) * 3.6f, gb.label(),
                        gb.automatic() ? "(자동)" : "(수동)", e.rpm()));
        if (e.turbo() != null) {
            sb.append(String.format("  부스트 %.2f bar", e.boost()));
        }
        float angle = (float) Math.toDegrees(car.driftAngle());
        if (angle > 10 && car.speedKmh() > 15) {
            sb.append(angle < 120 ? String.format("  드리프트 %.0f°", angle) : "  스핀");
        }
        if (scorer.chainPoints() > 0) {
            sb.append(String.format("  점수 %,d x%.1f", Math.round(scorer.chainPoints()), scorer.multiplier()));
        }
        sb.append(String.format("  |  총점 %,d  돈 %,d CR", scorer.total(), session.profile().money()));
        return sb.toString();
    }

    /** 바퀴가 처음 닿은 뒤, 위아래 속도의 부호가 바뀐 횟수(흔들림 크기 2cm/s 이상만)를 센다 */
    private void measureCar() {
        boolean anyContact = false;
        for (int i = 0; i < 4; i++) {
            anyContact |= car.wheel(i).contact();
        }
        carLanded |= anyContact;
        float vy = car.velocity().y();
        if (carLanded && !carReported && Math.abs(vy) > 0.02f
                && Math.signum(vy) != Math.signum(lastVerticalVelocity) && lastVerticalVelocity != 0) {
            bounces++;
        }
        if (Math.abs(vy) > 0.02f) {
            lastVerticalVelocity = vy;
        }
    }

    private void reportCar() {
        CarSpec spec = car.spec();
        Vec3 p = car.position();
        float above = p.y() - terrain.height(p.x(), p.z());
        Vec3 up = car.rotation().rotate(new Vec3(0, 1, 0));
        float tiltDeg = (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, up.y()))));
        float speed = car.velocity().length();
        float error = above - spec.restHeight();
        StringBuilder wheels = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            Car.WheelState w = car.wheel(i);
            wheels.append(String.format(" [%s 눌림 %.3fm 힘 %.0fN]", new String[]{"앞왼", "앞오", "뒤왼", "뒤오"}[i],
                    w.compression(), w.force()));
        }
        boolean ok = Math.abs(error) < 0.02f && speed < 0.05f && tiltDeg < 2f;
        Log.info(String.format("차 정지 확인 (%.0f초 뒤): 지면 위 %.3fm (계산 %.3fm, 차이 %+.3fm), 기울기 %.2f°, 속력 %.3f m/s, 출렁임 %d번 → %s",
                CAR_CHECK_SECONDS, above, spec.restHeight(), error, tiltDeg, speed, bounces, ok ? "정상" : "확인 필요"));
        Log.info("    바퀴:" + wheels);
        Log.info(String.format("    물리 계산 시간: 틱당 평균 %.3f ms (%d 서브스텝, 차 계산 포함)",
                physicsNanos / 1e6 / Math.max(1, physicsTicks), SUBSTEPS));
    }

    /** 상자가 지형 위에 멈췄는지: 속력 작음 + 중심이 지면보다 대략 반 변~대각선 반 길이만큼 위 */
    private void reportBoxes() {
        int ok = 0;
        for (int i = 0; i < boxBodies.size(); i++) {
            int body = boxBodies.get(i);
            float[] p = physics.position(body);
            float ground = terrain.height(p[0], p[2]);
            float above = p[1] - ground;
            float speed = physics.speed(body);
            boolean resting = speed < 0.05f && above > 0.3f && above < 1.0f;
            if (resting) {
                ok++;
            }
            if (!resting) {
                Log.info(String.format("    상자 %d: 위치 (%.1f, %.2f, %.1f), 지면 위 %.2fm, 속력 %.3f m/s  ← 확인 필요",
                        i, p[0], p[1], p[2], above, speed));
            }
        }
        Log.info("상자 정지 확인 (" + BOX_CHECK_SECONDS + "초 뒤): " + ok + "/" + boxBodies.size() + " 개 지형 위에 정지");
    }

    @Override
    public List<SceneObject> objects() {
        return Collections.unmodifiableList(objects);
    }

    @Override
    public FollowTarget followTarget() {
        // 그려지는 차체(보간된 위치)를 따라간다
        Tracked chassis = carTracks.get(0);
        Vec3 pos = chassis.object.model().transformPoint(Vec3.ZERO);
        Vec3 front = chassis.object.model().transformPoint(new Vec3(0, 0, -1)).sub(pos).normalize();
        // 드리프트할 때 차 옆모습이 보이도록 카메라 방향을 진행 방향 쪽으로 반쯤 돌린다
        Vec3 v = car.velocity();
        Vec3 flatV = new Vec3(v.x(), 0, v.z());
        if (flatV.length() > 5 && car.forwardSpeed() > 0) {
            front = front.add(flatV.normalize()).scale(0.5f);
        }
        return new FollowTarget(pos, front);
    }

    @Override
    public Environment environment() {
        Tracked body = carTracks.get(0);
        Vec3 pos = body.object.model().transformPoint(Vec3.ZERO);
        Vec3 front = body.object.model().transformPoint(new Vec3(0, 0, -1)).sub(pos);
        CarStyle style = carStyle;
        Vec3 ground = new Vec3(pos.x(), terrain.height(pos.x(), pos.z()), pos.z());
        return Environment.afternoon((float) simTime).withContactShadow(new Environment.ContactShadow(
                ground, front, style.width() / 2, style.length() / 2));
    }

    @Override
    public Vec3 cameraStart() {
        return new Vec3(CAR_SPAWN.x(), terrain.height(CAR_SPAWN.x(), CAR_SPAWN.z() + 7) + 3, CAR_SPAWN.z() + 7);
    }

    public Terrain terrain() {
        return terrain;
    }

    @Override
    public void close() {
        session.save();
        audio.close();
        physics.close();
    }
}
