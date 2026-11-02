package driftengine.vehicle;

import driftengine.core.Log;
import driftengine.math.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 차량 파일(cars/*.properties, UTF-8) 읽기. 파일에 없는 값은 내장 기본 차(BUILT_IN)의 값을 쓴다.
 * <p>
 * 토크 곡선 "engine.torque" 는 "회전수:토크" 를 쉼표로 이은 것. 터보 차는 스톡 부스트가 다 찼을 때의 실측(다이노) 곡선을 적는다.
 * 읽을 때 부스트 0 기준 곡선으로 바꿔 둔다(회전수별 부스트 배율로 나눔) → 튜닝으로 부스트를 바꾸면 토크가 그만큼 변한다.
 * 터보는 파일에 turbo.boost 가 있을 때만 단다(빠지면 자연흡기).
 * 스프링은 "정지 눌림(sag)", 댐퍼는 "감쇠비"로 적는다 (k = 바퀴 하중 / 눌림, c = 2ζ√(k·m)).
 */
public final class CarSpecLoader {

    /** 기본 차: 가상의 일본 로터리 트윈터보 쿠페 (90년대 FR 스포츠카 성능대) */
    static final String BUILT_IN = """
            name=RT-7
            brand=Kaze
            origin=JDM
            category=drift
            mass=1280
            body.width=1.76
            body.height=0.64
            body.length=4.30
            com.offsetY=-0.12
            wheelbase=2.43
            track.front=1.46
            track.rear=1.46
            weight.front=0.50
            suspension.mountY=0.0
            suspension.travel=0.25
            suspension.sag.front=0.06
            suspension.sag.rear=0.06
            suspension.damping=0.40
            antiroll.front=25000
            antiroll.rear=12000
            wheel.radius=0.32
            wheel.inertia=1.1
            tire.front.grip=1.12
            tire.front.peakSlip=0.10
            tire.front.peakAngle=8
            tire.front.shape=1.50
            tire.front.loadSensitivity=0.12
            tire.rear.grip=1.10
            tire.rear.peakSlip=0.10
            tire.rear.peakAngle=8
            tire.rear.shape=1.42
            tire.rear.loadSensitivity=0.12
            steer.maxAngle=45
            steer.rate=220
            brake.torque=5200
            brake.frontBias=0.62
            brake.handbrake=2200
            brake.abs=true
            aero.dragArea=0.62
            aero.downforceArea=0
            aero.downforceFront=0.45
            engine.type=rotary
            engine.cylinders=2
            engine.idle=900
            engine.launch=4500
            engine.redline=8500
            engine.torque=1000:150, 2000:200, 3000:250, 4000:285, 5000:294, 6000:290, 6500:281, 7000:265, 8000:225, 8500:205
            engine.inertia=0.12
            engine.brake=45
            turbo.boost=0.5
            turbo.spoolStart=2200
            turbo.fullBoost=4200
            turbo.spoolTime=0.55
            turbo.torquePerBar=0.85
            gearbox.ratios=3.483, 2.015, 1.391, 1.000, 0.719
            gearbox.reverse=3.28
            gearbox.final=4.10
            gearbox.shiftTime=0.18
            gearbox.efficiency=0.90
            drive.layout=RWD
            drive.frontShare=0
            lsd.preload=180
            lsd.lock=0.55
            assist.countersteer=0.6
            assist.maxAngle=55
            assist.yaw=0.5
            """;

    private CarSpecLoader() {
    }

    public static CarSpec builtIn() {
        return parse("built-in", read(BUILT_IN));
    }

    /** cars 폴더의 *.properties 를 파일 이름 순서로 읽는다. 틀린 파일은 로그를 남기고 건너뛴다. 하나도 없으면 내장 기본 차 */
    public static List<CarSpec> loadDirectory(Path dir) {
        List<CarSpec> cars = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList();
            } catch (IOException e) {
                Log.warn("차량 폴더를 읽지 못함: " + dir + " (" + e.getMessage() + ")");
                files = List.of();
            }
            for (Path file : files) {
                String id = file.getFileName().toString().replaceFirst("\\.properties$", "");
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Properties p = new Properties();
                    p.load(r);
                    cars.add(parse(id, p));
                } catch (IOException | RuntimeException e) {
                    Log.warn("차량 파일 건너뜀: " + file.getFileName() + " → " + e.getMessage());
                }
            }
        }
        if (cars.isEmpty()) {
            Log.info("차량 파일이 없어 내장 기본 차를 씁니다 (" + dir.toAbsolutePath() + ")");
            cars.add(builtIn());
        }
        return cars;
    }

    public static Properties read(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    /** 파일 값 + 빠진 값은 내장 기본값 */
    public static CarSpec parse(String id, Properties file) {
        Properties base = read(BUILT_IN);
        Properties p = new Properties();
        p.putAll(base);
        p.putAll(file);
        Values v = new Values(p, id);

        float mass = v.f("mass");
        float weightFront = v.f("weight.front");
        float sagF = v.f("suspension.sag.front");
        float sagR = v.f("suspension.sag.rear");
        float zeta = v.f("suspension.damping");
        float loadF = mass * CarSpec.GRAVITY * weightFront / 2;
        float loadR = mass * CarSpec.GRAVITY * (1 - weightFront) / 2;
        float kF = loadF / sagF;
        float kR = loadR / sagR;
        float cF = (float) (2 * zeta * Math.sqrt(kF * loadF / CarSpec.GRAVITY));
        float cR = (float) (2 * zeta * Math.sqrt(kR * loadR / CarSpec.GRAVITY));

        EngineSpec engine = parseEngine(v, file.getProperty("turbo.boost") != null);
        float[] ratios = v.floats("gearbox.ratios");
        GearboxSpec gearbox = new GearboxSpec(ratios, v.f("gearbox.reverse"), v.f("gearbox.final"),
                v.f("gearbox.shiftTime"), v.f("gearbox.efficiency"));

        return new CarSpec(id, v.s("name"), v.s("brand"), v.s("origin"), v.s("category").toLowerCase(Locale.ROOT),
                mass, new Vec3(v.f("body.width") / 2, v.f("body.height") / 2, v.f("body.length") / 2), v.f("com.offsetY"),
                v.f("wheelbase"), v.f("track.front"), v.f("track.rear"), weightFront, v.f("suspension.mountY"),
                v.f("suspension.travel"), v.f("wheel.radius"), v.f("wheel.inertia"),
                kF, kR, cF, cR, v.f("antiroll.front"), v.f("antiroll.rear"),
                tire(v, "tire.front."), tire(v, "tire.rear."),
                (float) Math.toRadians(v.f("steer.maxAngle")), (float) Math.toRadians(v.f("steer.rate")),
                v.f("brake.torque"), v.f("brake.frontBias"), v.f("brake.handbrake"), v.b("brake.abs"),
                v.f("aero.dragArea"), v.f("aero.downforceArea"), v.f("aero.downforceFront"),
                engine, gearbox, DriveLayout.valueOf(v.s("drive.layout").toUpperCase(Locale.ROOT)),
                v.f("drive.frontShare"), v.f("lsd.preload"), v.f("lsd.lock"), v.f("assist.countersteer"),
                (float) Math.toRadians(v.f("assist.maxAngle")), v.f("assist.yaw"), 0f, 0f, 0f, 0f);
    }

    private static TireSpec tire(Values v, String prefix) {
        return new TireSpec(v.f(prefix + "grip"), v.f(prefix + "peakSlip"),
                (float) Math.toRadians(v.f(prefix + "peakAngle")), v.f(prefix + "shape"), v.f(prefix + "loadSensitivity"));
    }

    private static EngineSpec parseEngine(Values v, boolean fileHasTurbo) {
        EngineSpec.EngineType type = EngineSpec.EngineType.valueOf(v.s("engine.type").toUpperCase(Locale.ROOT));
        String[] points = v.s("engine.torque").split(",");
        float[] rpm = new float[points.length];
        float[] stock = new float[points.length];
        for (int i = 0; i < points.length; i++) {
            String[] pair = points[i].trim().split(":");
            if (pair.length != 2) {
                throw new IllegalArgumentException("engine.torque 형식은 회전수:토크 (" + points[i].trim() + ")");
            }
            rpm[i] = Float.parseFloat(pair[0].trim());
            stock[i] = Float.parseFloat(pair[1].trim());
        }
        // 터보는 파일에 turbo.boost 가 있을 때만 (기본 차의 터보 값을 물려받지 않음). 나머지 터보 값은 기본값을 써도 된다
        float boost = type == EngineSpec.EngineType.ELECTRIC || !fileHasTurbo ? 0 : v.f("turbo.boost");
        TurboSpec turbo = boost > 0 ? new TurboSpec(boost, v.f("turbo.spoolStart"), v.f("turbo.fullBoost"),
                v.f("turbo.spoolTime"), v.f("turbo.torquePerBar")) : null;
        float idle = type == EngineSpec.EngineType.ELECTRIC ? 0 : v.f("engine.idle");
        float launch = type == EngineSpec.EngineType.ELECTRIC ? 0 : v.f("engine.launch");
        // 스톡 곡선 → 부스트 0 곡선 (스톡 부스트가 다 찼을 때 배율로 나눔)
        EngineSpec probe = new EngineSpec(type, v.i("engine.cylinders"), idle, launch, v.f("engine.redline"),
                rpm, stock, v.f("engine.inertia"), v.f("engine.brake"), turbo);
        float[] base = new float[rpm.length];
        for (int i = 0; i < rpm.length; i++) {
            float m = probe.fullLoadTorqueAt(rpm[i]) / probe.baseTorqueAt(rpm[i]);
            base[i] = stock[i] / m;
        }
        return new EngineSpec(type, probe.cylinders(), idle, launch, probe.redlineRpm(), rpm, base,
                probe.inertia(), probe.brakeTorque(), turbo);
    }

    /** 값 읽기 + 어느 키가 틀렸는지 알려주는 오류 */
    private record Values(Properties p, String id) {
        String s(String key) {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("'" + key + "' 값이 없습니다");
            }
            return value.trim();
        }

        float f(String key) {
            try {
                return Float.parseFloat(s(key));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + key + "' 는 숫자여야 합니다: " + p.getProperty(key));
            }
        }

        int i(String key) {
            return Math.round(f(key));
        }

        boolean b(String key) {
            return Boolean.parseBoolean(s(key));
        }

        float[] floats(String key) {
            String[] parts = s(key).split(",");
            float[] out = new float[parts.length];
            for (int k = 0; k < parts.length; k++) {
                out[k] = Float.parseFloat(parts[k].trim());
            }
            return out;
        }
    }
}
