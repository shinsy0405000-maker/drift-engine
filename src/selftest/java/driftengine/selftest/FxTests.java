package driftengine.selftest;

import driftengine.fx.SkidMarks;
import driftengine.fx.TireSmoke;
import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.render.Bloom;
import driftengine.render.EffectsRenderer;
import driftengine.render.FrameUniforms;
import driftengine.render.CascadedShadows;
import driftengine.render.Culling;
import driftengine.scene.Camera;
import driftengine.scene.Effects;
import driftengine.scene.Environment;
import driftengine.scene.mesh.VertexFormat;
import driftengine.vehicle.Car;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriverInput;

import java.nio.FloatBuffer;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** B-2: 해 그림자 행렬, 블룸 단계, 효과 정점, 스키드 자국, 타이어 연기 (GPU 없이 계산만) */
final class FxTests {

    private FxTests() {
    }

    static void run() {
        test("단계 그림자: 거리별 단계, 해 쪽 물체 포함, 텍셀 단위로 맞춰 떨림 없음", FxTests::shadowMatrix);
        test("프레임 uniform: 단계 행렬·효과 설정 배치, 크기 720", FxTests::uniformLayout);
        test("건너뛰기: 화면 밖 물체 제외, 그림자 단계는 앞 단계가 맡은 물체 제외", FxTests::culling);
        test("프레임 uniform: 셰이더 frame.glsl 의 필드 순서와 같음", FxTests::uniformMatchesShader);
        test("블룸 단계 크기: 절반씩, 최대 6단계, 너무 작으면 멈춤", FxTests::bloomLevels);
        test("효과 정점: 스키드 띠 + 카메라를 보는 연기, 먼 것부터", FxTests::effectVertices);
        test("스키드 자국: 이어진 띠, 미끄러질 때만, 순간이동하면 끊김, 고리 버퍼", FxTests::skidMarks);
        test("타이어 연기: 뿜는 양, 떠오르며 부풀고 옅어짐, 수명 끝나면 사라짐, 상한", FxTests::smoke);
        test("실제 주행: 핸드브레이크 드리프트에서 자국·연기가 생기고, 그냥 달릴 때는 안 생김", FxTests::drivingFx);
    }

    private static float[] project(Mat4 m, Vec3 p) {
        float[] c = m.transform(p.x(), p.y(), p.z(), 1);
        return new float[]{c[0] / c[3], c[1] / c[3], c[2] / c[3]};
    }

    private static void shadowMatrix() {
        Vec3 sun = new Vec3(0.45f, 0.75f, 0.35f).normalize();
        Vec3 cam = new Vec3(12.3f, 1.6f, -40.7f);
        Vec3 fwd = new Vec3(0, -0.1f, -1).normalize();
        int size = 4096;
        Mat4[] m = new Mat4[CascadedShadows.CASCADES];
        float[] texel = new float[CascadedShadows.CASCADES];
        for (int i = 0; i < m.length; i++) {
            m[i] = new Mat4();
        }
        CascadedShadows.computeCascades(cam, fwd, (float) Math.toRadians(60), 16f / 9f, 0.1f, sun, size, m, texel);
        for (int i = 1; i < m.length; i++) {
            check(texel[i] > texel[i - 1], "먼 단계일수록 텍셀이 큼: " + texel[i - 1] + " < " + texel[i]);
        }
        check(texel[0] < 0.01f, "가까운 단계 텍셀 1cm 미만: " + texel[0]);
        float[] near = project(m[0], cam.add(fwd.scale(5)));
        check(inside(near), "5m 앞은 1단계 안: " + near[0] + ", " + near[1] + ", " + near[2]);
        float[] mid = project(m[2], cam.add(fwd.scale(50)));
        check(inside(mid) && !inside(project(m[0], cam.add(fwd.scale(50)))), "50m 앞은 3단계 안, 1단계 밖");
        float[] far = project(m[3], cam.add(fwd.scale(240)));
        check(inside(far), "240m 앞은 4단계 안");
        Vec3 p = cam.add(fwd.scale(6));
        float[] up = project(m[0], p.add(sun.scale(5)));
        check(up[2] < project(m[0], p)[2], "해 쪽으로 5m = 해에 더 가까움");
        // 언덕 그림자: 해 쪽 150m 위 물체도 깊이 범위 안
        check(project(m[1], cam.add(fwd.scale(20)).add(sun.scale(150)))[2] >= 0f, "해 쪽 150m 물체도 그림자를 드리움");
        // 텍셀 맞춤: 카메라가 조금 움직여도 고정점은 정확히 텍셀 단위로만 움직인다
        float texelNdc = 2f / size;
        Vec3 world = cam.add(fwd.scale(4));
        float[] a0 = project(m[0], world);
        Mat4[] m2 = {new Mat4(), new Mat4(), new Mat4(), new Mat4()};
        CascadedShadows.computeCascades(cam.add(new Vec3(0.0123f, 0, 0.0071f)), fwd, (float) Math.toRadians(60), 16f / 9f,
                0.1f, sun, size, m2, new float[4]);
        float[] a1 = project(m2[0], world);
        float dx = (a1[0] - a0[0]) / texelNdc;
        float dy = (a1[1] - a0[1]) / texelNdc;
        check(Math.abs(dx - Math.round(dx)) < 0.02f && Math.abs(dy - Math.round(dy)) < 0.02f,
                "텍셀 단위 이동 (떨림 없음): " + dx + ", " + dy);
        // 해가 머리 위여도 행렬이 망가지지 않음
        CascadedShadows.computeCascades(cam, fwd, 1f, 1.5f, 0.1f, new Vec3(0.01f, 1, 0), size, m2, new float[4]);
        check(inside(project(m2[0], cam.add(fwd.scale(3)))), "해가 머리 위일 때도 정상");
    }

    private static void culling() {
        Camera cam = new Camera();
        cam.update(1280, 720, 0, 0);
        float[] planes = new float[24];
        Culling.planes(cam.viewProjection(), planes);
        Vec3 p = cam.position();
        Vec3 f = cam.forward();
        Vec3 ahead = p.add(f.scale(20));
        Vec3 behind = p.add(f.scale(-20));
        check(Culling.visible(planes, new float[]{ahead.x(), ahead.y(), ahead.z(), 1}, 0), "20m 앞은 보임");
        check(!Culling.visible(planes, new float[]{behind.x(), behind.y(), behind.z(), 1}, 0), "20m 뒤는 안 보임");
        check(Culling.visible(planes, new float[]{behind.x(), behind.y(), behind.z(), 25}, 0), "뒤라도 크면 걸침");
        Vec3 side = p.add(f.scale(5)).add(cam.right().scale(60));
        check(!Culling.visible(planes, new float[]{side.x(), side.y(), side.z(), 1}, 0), "옆 멀리는 안 보임");

        // 그림자 단계: 카메라 6m 앞 차 (반지름 2.5)
        Vec3 sun = new Vec3(0.4f, 0.75f, 0.3f).normalize();
        Mat4[] m = {new Mat4(), new Mat4(), new Mat4(), new Mat4()};
        CascadedShadows.computeCascades(p, f, cam.fovY(), cam.aspect(), cam.near(), sun, 4096, m, new float[4]);
        Vec3 carPos = p.add(f.scale(6));
        float[] car = {carPos.x(), carPos.y(), carPos.z(), 2.5f};
        boolean[] want = {true, true, false, false};
        for (int c = 0; c < 4; c++) {
            Culling.planes(m[c], planes);
            check(CascadedShadows.needed(c, car, 1.3f, p, sun.y(), planes) == want[c], "가까운 차: 단계 " + c + " 그림 = " + want[c]);
        }
        // 70m 앞 큰 언덕 조각 (반지름 30): 1단계 밖, 3·4단계 필요
        Vec3 hill = p.add(f.scale(70));
        float[] chunk = {hill.x(), hill.y(), hill.z(), 30f};
        Culling.planes(m[0], planes);
        check(!CascadedShadows.needed(0, chunk, 8f, p, sun.y(), planes), "먼 언덕: 1단계 안 그림");
        Culling.planes(m[2], planes);
        check(CascadedShadows.needed(2, chunk, 8f, p, sun.y(), planes), "먼 언덕: 3단계 그림");
        // 평평한 조각(높이 폭 0.3m)이 카메라 바로 밑이면 4단계는 건너뜀
        Vec3 under = p.add(f.scale(10));
        float[] flat = {under.x(), under.y(), under.z(), 23f};
        Culling.planes(m[3], planes);
        check(!CascadedShadows.needed(3, flat, 0.3f, p, sun.y(), planes), "가까운 평지 조각: 4단계 안 그림");
        // 해가 낮으면 그림자가 길어져 더 먼 단계까지 그림
        Culling.planes(m[2], planes);
        check(CascadedShadows.needed(2, car, 1.3f, p, 0.06f, planes), "해가 낮으면(고도 3.4도) 차 그림자 30m → 3단계도 그림");
        check(CascadedShadows.needed(3, car, 1.3f, p, 0.02f, planes), "해가 지평선이면 모든 단계");
    }

    private static boolean inside(float[] c) {
        return Math.abs(c[0]) <= 1 && Math.abs(c[1]) <= 1 && c[2] >= 0 && c[2] <= 1;
    }

    private static void uniformLayout() {
        Camera cam = new Camera();
        cam.update(1280, 720, 0, 0);
        Mat4[] cs = new Mat4[CascadedShadows.CASCADES];
        for (int i = 0; i < cs.length; i++) {
            cs[i] = new Mat4().translation(i + 1, 0, 0);
        }
        float[] texel = {0.002f, 0.006f, 0.02f, 0.07f};
        FrameUniforms.Extras x = new FrameUniforms.Extras(cs, texel, 4096, true, true, 0.0025f, 77, 1920, 1080);
        FloatBuffer buf = FloatBuffer.allocate(FrameUniforms.FLOAT_COUNT);
        FrameUniforms.pack(cam, false, 0, Environment.afternoon(0), x, buf);
        check(buf.position() == FrameUniforms.FLOAT_COUNT && FrameUniforms.SIZE_BYTES == 720, "720 바이트");
        boolean same = true;
        for (int c = 0; c < cs.length; c++) {
            float[] cm = cs[c].toColumnMajorArray();
            for (int i = 0; i < 16; i++) {
                same &= buf.get(FrameUniforms.CASCADE_OFFSET_FLOATS + c * 16 + i) == cm[i];
            }
        }
        check(same, "단계 행렬 4개가 float 88 부터 (열 우선)");
        check(buf.get(152) == CascadedShadows.SPLITS[0] && buf.get(155) == CascadedShadows.SPLITS[3], "단계 끝 거리 (바이트 608)");
        int sp = FrameUniforms.SHADOW_PARAMS_FLOATS;
        check(buf.get(sp) == 1f && buf.get(sp + 1) == 4096f, "그림자 켬 + 크기 (바이트 624)");
        int fe = FrameUniforms.FEATURES_FLOATS;
        check(buf.get(fe) == 1f && buf.get(fe + 1) == 1f && buf.get(fe + 2) == 0.0025f && buf.get(fe + 3) == 77f,
                "AO·도장입자·높이안개·프레임 (바이트 640)");
        check(buf.get(164) == 1920f && buf.get(165) == 1080f, "렌더 해상도 (바이트 656)");
        check(buf.get(176) == 0.002f && buf.get(179) == 0.07f, "단계별 텍셀 (바이트 704)");
        FloatBuffer off = FloatBuffer.allocate(FrameUniforms.FLOAT_COUNT);
        FrameUniforms.pack(cam, false, off);
        check(off.get(sp) == 0f && off.get(fe) == 0f, "설정이 없으면 그림자·AO 끔");
    }

    /** 셰이더 frame.glsl 의 UBO 배치가 Java 쪽 오프셋과 같은지 (글자로 확인) */
    private static void uniformMatchesShader() {
        java.nio.file.Path p = java.nio.file.Path.of("src/main/shaders/frame.glsl");
        if (!java.nio.file.Files.exists(p)) {
            return; // 원본 셰이더가 없는 실행 위치
        }
        String glsl;
        try {
            glsl = java.nio.file.Files.readString(p);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        String[] order = {"viewProjJittered", "viewProj", "prevViewProj", "invViewProj", "options", "cameraPos", "sunDir",
                "sunColor", "shadowCenter", "shadowForward", "cascade[4]", "cascadeFar", "shadowParams", "features",
                "viewport", "cameraForward", "fogParams", "cascadeTexel"};
        int[] bytes = {0, 64, 128, 192, 256, 272, 288, 304, 320, 336, 352, 608, 624, 640, 656, 672, 688, 704};
        int last = -1;
        for (int i = 0; i < order.length; i++) {
            int at = glsl.indexOf(" " + order[i] + ";");
            check(at > last, "frame.glsl 에 " + order[i] + " 가 순서대로 있음");
            int eol = glsl.indexOf('\n', at);
            check(glsl.substring(at, eol).contains("// " + bytes[i]), order[i] + " 주석 오프셋 " + bytes[i]);
            last = at;
        }
        check(bytes[bytes.length - 1] + 16 == FrameUniforms.SIZE_BYTES, "끝 = 720");
        check(bytes[10] / 4 == FrameUniforms.CASCADE_OFFSET_FLOATS && bytes[12] / 4 == FrameUniforms.SHADOW_PARAMS_FLOATS
                && bytes[13] / 4 == FrameUniforms.FEATURES_FLOATS, "Java 상수와 같음");
    }

    private static void bloomLevels() {
        List<int[]> l = Bloom.levelSizes(1920, 1080);
        check(l.size() == 6 && l.get(0)[0] == 960 && l.get(0)[1] == 540 && l.get(5)[0] == 30 && l.get(5)[1] == 16,
                "1920x1080 → 960x540 … 30x16 (6단계)");
        check(Bloom.levelSizes(20, 20).size() == 1, "20x20 → 1단계");
        check(Bloom.levelSizes(10, 10).isEmpty(), "10x10 → 없음");
    }

    private static void effectVertices() {
        Effects fx = new Effects();
        fx.addSkid(new float[]{0, 0, 0, 1, 0, 0, 1, 0, 2, 0, 0, 2}, 0.5f, 3, 5);
        fx.addSmoke(0, 1, -10, 1.0f, 0.3f, 0, 0.9f, 0.2f);
        fx.addSmoke(0, 1, -30, 2.0f, 0.4f, 0.7f, 0.85f, 0.6f);
        float[] out = new float[(Effects.MAX_SKID + Effects.MAX_SMOKE) * 4 * VertexFormat.FLOATS_PER_VERTEX];
        long[] keys = new long[Effects.MAX_SMOKE];
        Vec3 cam = new Vec3(0, 1, 0);
        Vec3 right = Vec3.UNIT_X;
        Vec3 up = Vec3.UNIT_Y;
        int quads = EffectsRenderer.build(fx, cam, right, up, out, keys);
        check(quads == 3, "사각형 3개: " + quads);
        int f = VertexFormat.FLOATS_PER_VERTEX;
        check(out[0] == 0 && out[2] == 0 && out[f] == 1 && out[9] == 0.5f && out[10] == 0 && out[12] == 3, "스키드 첫 정점: 위치·진하기·종류 0·v");
        check(out[2 * f + 11] == 1 && out[2 * f + 12] == 5, "스키드 셋째 정점 u=1, v=5");
        // 연기: 먼 것(z=-30)이 먼저
        int s1 = 4 * f;
        float cz = 0;
        for (int k = 0; k < 4; k++) {
            cz += out[s1 + k * f + 2];
        }
        check(Math.abs(cz / 4 + 30) < 1e-3f, "먼 연기가 먼저 그려짐 (중심 z " + cz / 4 + ")");
        check(out[s1 + 10] == 1, "연기 종류 1");
        check(out[s1 + 5] > 0.99f, "연기 법선이 카메라 쪽 (+z)");
        // 크기: 중심에서 모서리까지 = 크기·√2
        float dx = out[s1] - 0;
        float dy = out[s1 + 1] - 1;
        float dz = out[s1 + 2] + 30;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        check(Math.abs(d - 2 * (float) Math.sqrt(2)) < 1e-3f, "모서리 거리 " + d);
        check(Math.abs(dz) < 1e-4f, "카메라를 보는 평면 (z 같음)");
    }

    private static final SkidMarks.Ground FLAT = (x, z) -> 0.5f;

    private static void skidMarks() {
        SkidMarks s = new SkidMarks();
        for (int i = 0; i <= 40; i++) { // 0.125m 씩 5m 직진, 미끄러짐
            s.update(2, true, 0, -i * 0.125f, 0.8f, 0.24f, FLAT);
        }
        check(s.size() >= 18 && s.size() <= 21, "5m → 0.25m 조각 약 20개: " + s.size());
        Effects fx = new Effects();
        s.write(fx);
        float[] d = fx.skidData();
        boolean joined = true;
        for (int k = 0; k + 1 < fx.skidCount(); k++) {
            int a = k * Effects.SKID_FLOATS;
            int b = (k + 1) * Effects.SKID_FLOATS;
            // 앞 조각 끝(왼B 9..11, 오른B 6..8) = 다음 조각 시작(왼A 0..2, 오른A 3..5)
            for (int c = 0; c < 3; c++) {
                joined &= d[a + 9 + c] == d[b + c] && d[a + 6 + c] == d[b + 3 + c];
            }
        }
        check(joined, "틈 없이 이어짐");
        float width = Math.abs(d[0] - d[3]);
        check(Math.abs(width - 0.24f) < 1e-4f && Math.abs(d[1] - 0.525f) < 1e-5f, "폭 0.24m, 땅 + 2.5cm (" + width + ", " + d[1] + ")");
        check(fx.skidData()[12] > 0.5f, "진하기");

        int before = s.size();
        for (int i = 0; i < 20; i++) {
            s.update(2, true, 0, -5 - i * 0.2f, 0, 0.24f, FLAT); // 그립 회복
        }
        check(s.size() == before, "미끄러지지 않으면 안 생김");
        s.update(2, true, 0, -10, 0.8f, 0.24f, FLAT);
        s.update(2, true, 50, -10, 0.8f, 0.24f, FLAT); // 순간이동
        check(s.size() == before, "50m 순간이동은 띠를 잇지 않음");

        SkidMarks ring = new SkidMarks();
        for (int i = 0; i < SkidMarks.CAPACITY * 0.3 * 4 + 100; i++) {
            ring.update(0, true, i * 0.3f % 500, (i * 0.3f / 500) * 3, 1, 0.2f, FLAT);
        }
        check(ring.size() == SkidMarks.CAPACITY, "고리 버퍼 상한 " + ring.size());
        Effects all = new Effects();
        ring.write(all);
        float oldest = all.skidData()[12];
        float newest = all.skidData()[(all.skidCount() - 1) * Effects.SKID_FLOATS + 12];
        check(oldest < 0.1f * newest, "가장 오래된 것은 옅어짐 (" + oldest + " vs " + newest + ")");
    }

    private static void smoke() {
        TireSmoke s = new TireSmoke();
        float dt = 1 / 60f;
        for (int t = 0; t < 60; t++) {
            s.emit(2, 0, 0.5f, 0, 0, 0, 0, 1, dt);
            s.emit(3, 1.5f, 0.5f, 0, 0, 0, 0, 1, dt);
            s.update(dt, FLAT);
        }
        long rate = s.emitted();
        check(Math.abs(rate - 2 * TireSmoke.RATE) <= 2, "1초에 바퀴 둘 × " + TireSmoke.RATE + "개: " + rate);
        float y0 = s.y(0);
        float size0 = s.sizeOf(0);
        for (int t = 0; t < 60; t++) {
            s.update(dt, FLAT);
        }
        check(s.y(0) > y0 && s.sizeOf(0) > size0 * 1.3f, "떠오르고 부풂 (높이 " + y0 + "→" + s.y(0) + ", 크기 " + size0 + "→" + s.sizeOf(0) + ")");
        for (int t = 0; t < 60 * 5; t++) {
            s.update(dt, FLAT);
        }
        check(s.count() == 0, "수명이 끝나면 모두 사라짐: " + s.count());
        TireSmoke none = new TireSmoke();
        none.emit(0, 0, 0, 0, 0, 0, 0, 0, 1);
        check(none.count() == 0, "강도 0 이면 안 나옴");
        TireSmoke full = new TireSmoke();
        for (int t = 0; t < 600; t++) {
            for (int w = 0; w < 4; w++) {
                full.emit(w, 0, 0, 0, 0, 0, 0, 1, 0.1f);
            }
        }
        check(full.count() == TireSmoke.CAPACITY, "상한 " + full.count());
        Effects fx = new Effects();
        full.write(fx);
        check(fx.smokeCount() == TireSmoke.CAPACITY, "효과 목록에 다 들어감");
        check(TireSmoke.intensity(4, 1) == 0 && TireSmoke.intensity(18, 1) == 1 && TireSmoke.intensity(30, 0) == 0,
                "연기 세기: 6 m/s 아래 없음, 하중 없으면 없음");
        check(SkidMarks.intensity(2, 1) == 0 && SkidMarks.intensity(10.5f, 1) == 1, "자국 세기");
    }

    private static void drivingFx() {
        CarSpec spec = CarSpec.defaults();
        // 1) 그냥 달리기 (발진 직후 제외): 자국·연기 없음
        DriveSim cruise = new DriveSim(spec);
        cruise.launchAt(60 / 3.6f);
        SkidMarks cs = new SkidMarks();
        TireSmoke cm = new TireSmoke();
        for (int t = 0; t < 180; t++) {
            cruise.tick(DriverInput.NONE);
            feed(cruise.car, cs, cm);
        }
        check(cs.size() == 0 && cm.emitted() == 0, "그냥 굴러갈 때 자국 " + cs.size() + ", 연기 " + cm.emitted());
        // 2) 핸드브레이크 드리프트
        DriveSim sim = new DriveSim(spec);
        sim.launchAt(70 / 3.6f);
        SkidMarks s = new SkidMarks();
        TireSmoke m = new TireSmoke();
        for (int t = 0; t < 240; t++) {
            sim.tick(DriverInput.of(t > 24, false, true, false, t < 24));
            feed(sim.car, s, m);
        }
        check(s.size() > 20, "드리프트 자국 조각 " + s.size());
        check(m.emitted() > 30, "드리프트 연기 " + m.emitted());
    }

    private static void feed(Car car, SkidMarks s, TireSmoke m) {
        var v = car.velocity();
        for (int i = 0; i < 4; i++) {
            Car.WheelState w = car.wheel(i);
            float load = w.load() / car.spec().staticLoad(i);
            Vec3 p = w.contactPoint();
            s.update(i, w.contact(), p.x(), p.z(), SkidMarks.intensity(w.slideSpeed(), load), 0.24f, FLAT);
            m.emit(i, p.x(), p.y(), p.z(), v.x(), v.y(), v.z(), w.contact() ? TireSmoke.intensity(w.slideSpeed(), load) : 0, 1 / 60f);
        }
        m.update(1 / 60f, FLAT);
    }
}
