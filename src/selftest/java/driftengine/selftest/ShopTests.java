package driftengine.selftest;

import driftengine.game.GameSession;
import driftengine.game.Part;
import driftengine.game.Styling;
import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshData;
import driftengine.ui.FontAtlas;
import driftengine.ui.UiCanvas;
import driftengine.ui.UiInput;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarModels;
import driftengine.vehicle.visual.CarStyle;
import driftengine.vehicle.visual.Livery;
import driftengine.world.ShopUi;
import driftengine.world.Shops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 튜닝샵·스타일링샵: 위치·입구, 샵 화면 조작(구입·미리보기·되돌리기), 꾸미기가 모델에 반영, UI 그리기 도구 */
final class ShopTests {

    private static final UiInput UP = new UiInput(false, true, false, false, false, false, false);
    private static final UiInput DOWN = new UiInput(false, false, true, false, false, false, false);
    private static final UiInput LEFT = new UiInput(false, false, false, true, false, false, false);
    private static final UiInput RIGHT = new UiInput(false, false, false, false, true, false, false);
    private static final UiInput OK = new UiInput(false, false, false, false, false, true, false);
    private static final UiInput BACK = new UiInput(false, false, false, false, false, false, true);

    private ShopTests() {
    }

    static void run() {
        test("샵 위치: 두 곳이 연습장 안, 입구 원 안/밖 판정, 충돌 벽", ShopTests::layout);
        test("튜닝샵: 분류 7개, 부품 구입하면 단계·돈·차 다시 만들기", ShopTests::tuningShop);
        test("스타일링샵: ←→ 로 바로 미리보기, Enter 로 한 번에 구입, 나가면 사지 않은 건 되돌림", ShopTests::stylingShop);
        test("꾸미기 → 모델: 다른 차 휠을 크기에 맞춰 씀, 캘리퍼 색·틴트·무광이 메시에 반영", ShopTests::liveryModels);
        test("꾸미기 값: 저장 글자 왕복, 가격 (순정으로 되돌리기는 무료)", ShopTests::liveryValues);
        test("UI 그리기: 둥근 사각형·호·그림자가 범위 안, 화면 크기에 비례", ShopTests::canvas);
    }

    private static GameSession session() {
        try {
            List<CarCatalog.Entry> cars = CarCatalog.load(Path.of("cars"));
            Path dir = Files.createTempDirectory("shop");
            GameSession s = new GameSession(cars, dir.resolve("p.properties"), null);
            s.profile().profileMoneyForTest(500_000);
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void layout() {
        List<Shops.Shop> shops = Shops.layout((x, z) -> 0f);
        check(shops.size() == 2 && shops.get(0).kind() != shops.get(1).kind(), "튜닝·스타일링 두 곳");
        for (Shops.Shop s : shops) {
            float r = (float) Math.hypot(s.center().x(), s.center().z());
            check(r + 20 < 120, s.kind().label + ": 평지(반지름 120m) 안 (" + r + "m)");
            Vec3 e = s.entrance();
            check(s.atEntrance(e) && !s.atEntrance(e.add(new Vec3(5, 0, 0))), s.kind().label + ": 입구 원 판정");
            // 앞이 출발점 쪽
            Vec3 toSpawn = new Vec3(0 - s.center().x(), 0, -14 - s.center().z()).normalize();
            check(s.front().dot(toSpawn) > 0.99f, s.kind().label + ": 앞이 출발점을 봄");
            check(s.colliders().size() >= 8, "충돌 상자 " + s.colliders().size());
            MeshData m = Shops.mesh(s);
            check(m.triangleCount() > 500, "샵 메시 삼각형 " + m.triangleCount());
            // 무대 가운데에서 차 높이 공간이 비어 있음 (벽이 무대 위로 침범하지 않음)
            for (float[] b : s.colliders()) {
                float dx = b[0] - s.center().x(), dz = b[2] - s.center().z();
                check(Math.hypot(dx, dz) > Shops.Shop.PAD_RADIUS, "충돌 상자가 무대 밖");
            }
        }
    }

    private static final class FakeHost implements ShopUi.Host {
        int changed;
        int previews;
        CarStyle last;
        boolean exited;
        final List<String> toasts = new ArrayList<>();

        @Override
        public void carChanged() {
            changed++;
        }

        @Override
        public void preview(CarStyle style) {
            previews++;
            last = style;
        }

        @Override
        public void exit() {
            exited = true;
        }

        @Override
        public void toast(String message) {
            toasts.add(message);
        }
    }

    private static void tuningShop() {
        GameSession s = session();
        FakeHost h = new FakeHost();
        ShopUi ui = new ShopUi(s, Shops.Kind.TUNING, h);
        long money = s.profile().money();
        ui.input(OK); // 엔진 분류 → 흡배기
        ui.input(OK); // 구입
        check(s.profile().selectedCar().tuning().level(Part.ENGINE) == 1 && s.profile().money() < money && h.changed == 1,
                "흡배기 1단계 구입");
        ui.input(BACK);
        for (int i = 0; i < 2; i++) {
            ui.input(DOWN); // 기어 → 연료통
        }
        ui.input(OK);
        ui.input(OK);
        check(s.profile().selectedCar().tuning().level(Part.FUEL_TANK) == 1, "연료통 분류에서 경량 연료통 구입");
        ui.input(BACK);
        for (int i = 0; i < 4; i++) {
            ui.input(UP); // 연료통 → 기어 → 엔진 → 타이어(돌아감) → 하체
        }
        ui.input(OK);
        ui.input(DOWN);
        ui.input(OK); // 브레이크
        check(s.profile().selectedCar().tuning().level(Part.BRAKES) == 1, "하체 분류 두 번째 = 브레이크");
        // ← 두 번 = 한 단계 내리기 (반값 돌려받음), 한 번은 확인만
        long before = s.profile().money();
        ui.input(LEFT);
        check(s.profile().selectedCar().tuning().level(Part.BRAKES) == 1 && s.profile().money() == before, "← 한 번은 확인만");
        ui.input(LEFT);
        check(s.profile().selectedCar().tuning().level(Part.BRAKES) == 0 && s.profile().money() > before && h.changed == 4,
                "← 두 번 = 브레이크 순정으로, 돈 돌려받음");
        ui.input(LEFT); // 순정에서 ← = 분류로
        ui.input(BACK);
        check(h.exited, "분류에서 Backspace = 나가기");
        ShopUi.Focus f = ui.focus();
        check(f.distance() > 3 && f.distance() < 8, "카메라 거리 " + f.distance());
    }

    private static void stylingShop() {
        GameSession s = session();
        FakeHost h = new FakeHost();
        ShopUi ui = new ShopUi(s, Shops.Kind.STYLING, h);
        long money = s.profile().money();
        ui.input(DOWN); // 페인팅
        ui.input(OK);
        ui.input(RIGHT);
        ui.input(RIGHT); // 차체 색 두 번째 (펄 화이트)
        check(h.previews == 2 && h.last.livery().paint() == 1 && h.last.color().equals(Livery.PAINTS.get(1).linear()),
                "←→ 로 미리보기 (색 바뀜)");
        check(s.profile().selectedCar().livery().equals(Livery.STOCK) && s.profile().money() == money, "아직 안 삼");
        check(ui.pendingCost() == Styling.PAINT, "장바구니 " + ui.pendingCost());
        ui.input(DOWN);
        ui.input(RIGHT);
        ui.input(RIGHT);
        ui.input(RIGHT); // 마감: 무광
        ui.input(OK);
        check(s.profile().selectedCar().livery().paint() == 1 && s.profile().selectedCar().livery().finish() == 2
                        && s.profile().money() == money - Styling.PAINT - Styling.FINISH[2],
                "Enter 로 색 + 무광 한 번에 구입: " + s.profile().selectedCar().livery());
        // 바디킷: 스포일러 GT 윙을 입혀 보고, 사지 않고 나가면 되돌림
        ui.input(BACK);
        ui.input(UP);
        ui.input(OK);
        for (int i = 0; i < 3; i++) {
            ui.input(DOWN);
        }
        ui.input(RIGHT);
        ui.input(RIGHT);
        check(h.last.kit().spoiler() == 2, "GT 윙 미리보기");
        ui.input(BACK);
        ui.input(BACK);
        check(h.exited && h.last.kit().spoiler() == 0 && s.profile().selectedCar().tuning().kit().spoiler() == 0,
                "나가면 사지 않은 윙은 되돌림");
        check(h.last.livery().finish() == 2, "산 것(무광)은 그대로");
    }

    private static void liveryModels() {
        GameSession s = session();
        String car = s.profile().selectedCar().carId();
        if (!CarModels.available(car) || !CarModels.available("01-kaze-rt7")) {
            return;
        }
        CarStyle stock = s.garage().currentStyle();
        CarStyle swapped = s.garage().styleOf(car, stock.kit(), Livery.STOCK.withRimFrom("01-kaze-rt7"));
        MeshData own = CarModels.wheel(car, stock, true, 1);
        MeshData other = CarModels.wheel(car, swapped, true, 1);
        check(own != null && other != null && own != other, "휠 디자인 바꾸면 다른 메시");
        Vec3 a = own.boundsMax().sub(own.boundsMin());
        Vec3 b = other.boundsMax().sub(other.boundsMin());
        check(Math.abs(a.x() - b.x()) < 0.005f && Math.abs(a.y() - b.y()) < 0.005f && Math.abs(a.z() - b.z()) < 0.005f,
                "다른 차 휠이 이 차 크기에 맞춰짐: " + a + " vs " + b);
        MeshData left = CarModels.wheel(car, swapped, true, -1);
        check(Math.abs(left.boundsMin().x() + other.boundsMax().x()) < 1e-4f, "왼쪽은 거울");

        CarStyle yellow = s.garage().styleOf(car, stock.kit(), Livery.STOCK.withCaliper(1));
        MeshData cal = CarModels.caliper(car, yellow, true, 1);
        Vec3 want = Livery.CALIPERS.get(1).linear();
        check(cal != null && cal.color(0).sub(want).length() < 1e-4f, "캘리퍼 색 = 노랑");

        CarStyle dark = s.garage().styleOf(car, stock.kit(), Livery.STOCK.withTint(3));
        MeshData glass = CarModels.glass(car, dark, 0.5f);
        float maxTint = 0;
        for (int i = 0; i < glass.vertexCount(); i++) {
            maxTint = Math.max(maxTint, glass.material(i)[0]);
        }
        check(maxTint == 1f, "리무진 틴트 → 유리 재질 값 1 (" + maxTint + ")");

        CarStyle matte = s.garage().styleOf(car, stock.kit(), Livery.STOCK.withPaint(4).withFinish(2));
        MeshData body = CarModels.body(car, matte, 0.5f);
        boolean foundMatte = false;
        for (int i = 0; i < body.vertexCount() && !foundMatte; i++) {
            float[] mat = body.material(i);
            foundMatte = mat[2] == 0f && Math.abs(mat[1] - 0.62f) < 1e-4f && body.color(i).sub(Livery.PAINTS.get(4).linear()).length() < 1e-4f;
        }
        check(foundMatte, "무광 블랙 도장 (클리어코트 없음, 거칠기 0.62)");
    }

    private static void liveryValues() {
        Livery l = new Livery(3, 1, "02-hayate-sr15", 4, 2, 3);
        check(Livery.parse(l.text()).equals(l) && Livery.parse(Livery.STOCK.text()).equals(Livery.STOCK), "글자 왕복");
        check(Livery.parse(null).equals(Livery.STOCK), "없으면 순정");
        check(Styling.price(Livery.STOCK, l) == Styling.PAINT + Styling.FINISH[1] + Styling.RIM_DESIGN + Styling.RIM_COLOR
                + Styling.CALIPER + Styling.TINT[3], "모두 바꾸면 각 값의 합");
        check(Styling.price(l, Livery.STOCK) == 0, "순정으로 되돌리기는 무료");
        boolean threw = false;
        try {
            new Livery(99, 0, null, 0, 0, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "범위 밖 색 거부");
    }

    private static void canvas() {
        UiCanvas c = new UiCanvas(new FontAtlas());
        c.begin(1920, 1080);
        check(Math.abs(c.scale() - 1.5f) < 1e-6f, "1080p 배율 1.5");
        c.roundRect(100, 100, 300, 80, 20, 0xFFFFFFFF);
        c.arc(960, 540, 100, 120, 0, (float) Math.PI, 0xFF0000FF, 0x00FF00FF);
        c.shadow(500, 500, 200, 100, 30, 0x00000080);
        c.circle(50, 50, 10, 0xFFFFFFFF);
        float[] d = c.data();
        boolean inside = true;
        for (int i = 0; i < c.vertexCount(); i++) {
            float x = d[i * UiCanvas.FLOATS_PER_VERTEX];
            float y = d[i * UiCanvas.FLOATS_PER_VERTEX + 1];
            inside &= x >= 39.9f && x <= 1080.1f && y >= 39.9f && y <= 660.1f;
        }
        check(inside && c.vertexCount() % 3 == 0 && c.vertexCount() > 54, "정점 " + c.vertexCount() + "개, 모두 그린 범위 안");
        check(UiCanvas.lerpColor(0x000000FF, 0xFFFFFFFF, 0.5f) == 0x808080FF, "색 섞기");
        check(UiCanvas.alpha(0xFFFFFFFF, 0.5f) == 0xFFFFFF80, "투명도");
    }
}
