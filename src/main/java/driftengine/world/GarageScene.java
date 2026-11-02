package driftengine.world;

import driftengine.core.Log;
import driftengine.math.Quat;
import driftengine.math.Vec3;
import driftengine.scene.Environment;
import driftengine.scene.Scene;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriverInput;
import driftengine.vehicle.visual.CarModels;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarStyle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 차고(쇼룸): 물리 없이 차 한 대를 세워 두고 카메라가 천천히 돈다. N 으로 다음 차.
 * 겉모양 확인용 (물리 엔진 없이 돌아가서 클라우드 소프트웨어 Vulkan 스크린샷에도 쓴다).
 * <p>
 * 개발용 시스템 속성: drift.car = 시작 차 번호(0부터), drift.orbit = 카메라 각도(도, 주면 고정),
 * drift.kit = 바디킷 "앞,옆,뒤,스포일러,와이드" (모든 차에 적용)
 */
public final class GarageScene implements Scene {

    private final List<SceneObject> objects = new ArrayList<>();
    private final List<CarCatalog.Entry> catalog;
    /** 지금 보이는 차 한 대의 물체들 (차를 바꾸면 새로 만들고, 렌더러가 안 쓰게 된 옛 메시를 GPU 에서 내린다) */
    private final List<SceneObject> carObjects = new ArrayList<>();
    private int index;
    private double time;
    private DriverInput pending = DriverInput.NONE;
    private final Float fixedOrbit;

    public GarageScene() {
        List<CarCatalog.Entry> loaded = CarCatalog.load(Path.of("cars"));
        String kit = System.getProperty("drift.kit");
        if (kit != null) {
            List<CarCatalog.Entry> withKit = new ArrayList<>();
            for (CarCatalog.Entry e : loaded) {
                withKit.add(new CarCatalog.Entry(e.spec(), e.style().withKit(driftengine.vehicle.visual.BodyKit.parse(kit)), e.sound()));
            }
            loaded = withKit;
        }
        catalog = loaded;
        objects.add(new SceneObject("garage-floor", floor()).setCastsShadow(false));
        index = Math.floorMod(Integer.getInteger("drift.car", 0), catalog.size());
        String orbit = System.getProperty("drift.orbit");
        fixedOrbit = orbit != null ? Float.parseFloat(orbit) : null;
        show(index);
        Log.info("차고: 차 " + catalog.size() + "대, N 다음 차, C 자유 카메라");
    }

    /** 지름 60m 아스팔트 원판 (y = 0) */
    private static MeshData floor() {
        MeshBuilder b = new MeshBuilder("garage-floor");
        b.material(Material.ASPHALT);
        Vec3 color = new Vec3(0.06f, 0.06f, 0.065f);
        int seg = 64;
        int center = b.vertex(Vec3.ZERO, Vec3.UNIT_Y, color);
        int first = -1;
        int prev = -1;
        for (int i = 0; i <= seg; i++) {
            double a = 2 * Math.PI * i / seg;
            int v = b.vertex(new Vec3((float) Math.cos(a) * 30, 0, (float) Math.sin(a) * 30), Vec3.UNIT_Y, color);
            if (prev >= 0) {
                b.triangle(center, v, prev);
            } else {
                first = v;
            }
            prev = v;
        }
        return b.build();
    }

    private void show(int i) {
        objects.removeAll(carObjects);
        carObjects.clear();
        CarCatalog.Entry e = catalog.get(i);
        CarSpec spec = e.spec();
        CarStyle style = e.style();
        Vec3 bodyPos = new Vec3(0, spec.restHeight(), 0);
        Vec3 one = new Vec3(1, 1, 1);
        carObjects.add(new SceneObject("car-" + spec.id(), CarModels.bodyOrBuilt(spec, style, spec.restHeight()))
                .place(bodyPos, 0, 0, 0, 1, one));
        MeshData glass = CarModels.glass(spec.id(), style, spec.restHeight());
        if (glass != null) {
            carObjects.add(new SceneObject("glass-" + spec.id(), glass).setTransparent(true).place(bodyPos, 0, 0, 0, 1, one));
        }
        for (int w = 0; w < 4; w++) {
            Vec3 c = bodyPos.add(spec.mount(w)).add(new Vec3(0, -(spec.restLength() - spec.staticSag(w)), 0));
            Quat q = Quat.IDENTITY;
            carObjects.add(new SceneObject("wheel-" + w, CarModels.wheelOrBuilt(spec, style, w))
                    .place(c, q.x(), q.y(), q.z(), q.w(), one));
            MeshData cal = CarModels.caliper(spec.id(), style, CarSpec.isFront(w), w % 2 == 0 ? -1 : 1);
            if (cal != null) {
                carObjects.add(new SceneObject("caliper-" + w, cal).place(c, q.x(), q.y(), q.z(), q.w(), one));
            }
        }
        objects.addAll(carObjects);
        Log.info("차고: " + spec.summary() + " | 바디킷 " + e.style().kit().text());
    }

    @Override
    public void beginFrame() {
        for (SceneObject o : objects) {
            o.beginFrame();
        }
    }

    @Override
    public void drive(DriverInput input) {
        pending = input;
    }

    @Override
    public void update(double simTimeSeconds, double dtSeconds) {
        time = simTimeSeconds;
        if (pending.nextCar()) {
            index = (index + 1) % catalog.size();
            show(index);
        }
        pending = DriverInput.NONE;
    }

    @Override
    public List<SceneObject> objects() {
        return Collections.unmodifiableList(objects);
    }

    /** 카메라: 차 주위를 천천히 돈다 (추적 카메라가 이 "앞 방향" 뒤쪽에 선다) */
    @Override
    public FollowTarget followTarget() {
        float deg = fixedOrbit != null ? fixedOrbit : (float) (time * 12 % 360);
        double a = Math.toRadians(deg);
        Vec3 forward = new Vec3((float) -Math.sin(a), 0, (float) -Math.cos(a));
        return new FollowTarget(new Vec3(0, 0.25f, 0), forward);
    }

    @Override
    public Environment environment() {
        CarCatalog.Entry e = catalog.get(index);
        return Environment.afternoon((float) time).withContactShadow(new Environment.ContactShadow(
                Vec3.ZERO, new Vec3(0, 0, -1), e.style().width() / 2, e.style().length() / 2));
    }

    @Override
    public String statusText() {
        CarCatalog.Entry e = catalog.get(index);
        return "차고: " + e.spec().brand() + " " + e.spec().name() + " (" + (index + 1) + "/" + catalog.size() + ")  N 다음 차";
    }

    @Override
    public Vec3 cameraStart() {
        return new Vec3(4, 1.6f, 5);
    }
}
