package driftengine.world;

import driftengine.game.CarClass;
import driftengine.game.Club;
import driftengine.game.ClubEvent;
import driftengine.game.DriftScorer;
import driftengine.game.GameSession;
import driftengine.game.Garage;
import driftengine.game.PerformanceRating;
import driftengine.game.Profile;
import driftengine.game.Tuning;
import driftengine.ui.Theme;
import driftengine.ui.UiCanvas;
import driftengine.ui.UiCanvas.Align;
import driftengine.ui.UiInput;
import driftengine.vehicle.Car;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.TractionControl;
import driftengine.vehicle.visual.CarCatalog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 화면 UI: 계기판(원형 회전계, 속도, 기어, 부스트, TC), 드리프트 점수, 샵 표시, 알림, Tab 메뉴(차고, 딜러쉽, 클럽, 샵으로 이동).
 * 튜닝·바디킷은 맵의 샵(ShopUi)에서 한다.
 */
final class GameUi {

    enum Screen { MAIN, GARAGE, DEALER, CLUBS, EVENTS, ZONES }

    /** 메뉴 동작을 실제로 하는 쪽 (TerrainScene) */
    interface Host {
        void carChanged();

        void startEvent(Club club, ClubEvent event);

        Car car();

        DriftScorer scorer();

        EventRun eventRun();

        /** 샵 앞으로 이동 */
        void travelTo(Shops.Kind kind);

        /** 다른 지역으로 이동 */
        void travelZone(driftengine.world.map.Zone zone);

        driftengine.world.map.Zone zone();

        /** 서킷 자유 주행 랩 기록 (없으면 null) */
        LapTimer practice();
    }

    /** 화면 위 샵 표시 (월드 → 화면 좌표, 거리) */
    record Marker(Shops.Kind kind, float x, float y, float distance, boolean prompt) {
    }

    private final GameSession session;
    private final Host host;
    private boolean open;
    private final Deque<Screen> stack = new ArrayDeque<>();
    private final Deque<Integer> selections = new ArrayDeque<>();
    private int selection;
    private Club selectedClub;
    private final List<String> toasts = new ArrayList<>();
    private final List<Float> toastTimes = new ArrayList<>();
    private float bankFlash;
    private long bankFlashPoints;
    private float time;
    private float shownRpm;

    GameUi(GameSession session, Host host) {
        this.session = session;
        this.host = host;
    }

    boolean open() {
        return open;
    }

    void toast(String message) {
        toasts.add(message);
        toastTimes.add(3.2f);
        if (toasts.size() > 4) {
            toasts.remove(0);
            toastTimes.remove(0);
        }
    }

    void banked(long points) {
        bankFlash = 2.0f;
        bankFlashPoints = points;
    }

    void update(float dt) {
        time += dt;
        for (int i = toastTimes.size() - 1; i >= 0; i--) {
            float t = toastTimes.get(i) - dt;
            if (t <= 0) {
                toasts.remove(i);
                toastTimes.remove(i);
            } else {
                toastTimes.set(i, t);
            }
        }
        bankFlash = Math.max(0, bankFlash - dt);
    }

    // ------------------------------------------------------------------ 입력

    void input(UiInput in) {
        if (in.menu()) {
            open = !open;
            stack.clear();
            selections.clear();
            selection = 0;
            if (!open) {
                session.save();
            }
            return;
        }
        if (!open) {
            return;
        }
        Screen screen = stack.isEmpty() ? Screen.MAIN : stack.peek();
        int count = itemCount(screen);
        if (in.up()) {
            selection = (selection + count - 1) % Math.max(1, count);
        }
        if (in.down()) {
            selection = (selection + 1) % Math.max(1, count);
        }
        if (in.back()) {
            if (stack.isEmpty()) {
                open = false;
                session.save();
            } else {
                stack.pop();
                selection = selections.isEmpty() ? 0 : selections.pop();
            }
            return;
        }
        if (in.confirm()) {
            confirm(screen);
        }
    }

    private void push(Screen s) {
        stack.push(s);
        selections.push(selection);
        selection = 0;
    }

    private Garage garage() {
        return session.garage();
    }

    private Profile profile() {
        return session.profile();
    }

    private int itemCount(Screen s) {
        return switch (s) {
            case MAIN -> MAIN_ITEMS.length;
            case GARAGE -> profile().cars().size();
            case DEALER -> dealerItems().size();
            case CLUBS -> session.clubs().size();
            case EVENTS -> selectedClub == null ? 0 : selectedClub.events().size();
            case ZONES -> driftengine.world.map.Zone.values().length;
        };
    }

    private static final String[] MAIN_ITEMS = {"내 차고", "딜러쉽", "클럽", "지역 이동", "튜닝샵으로 이동", "스타일링샵으로 이동", "계속 달리기"};
    private static final String[] MAIN_HINTS = {"가진 차 고르기", "새 차 사기", "이벤트에 도전하고 상금 받기",
            "연습장 · 용인 서킷 · 태백 고갯길 · 도시", "엔진 · 기어 · 연료통 · 다운포스 · 경량 · 하체 · 타이어",
            "바디킷 · 페인팅 · 휠 · 캘리퍼 · 틴트", "메뉴 닫기"};
    static final String[] ZONE_HINTS = {"드리프트 연습장 + 튜닝샵 · 스타일링샵", "3.6km 서킷, 출발선을 지나면 랩 시간 기록",
            "3.1km 다운힐, 헤어핀 3개", "6×6 블록 시가지, 주차장 드리프트"};

    private void confirm(Screen screen) {
        switch (screen) {
            case MAIN -> {
                switch (selection) {
                    case 0 -> push(Screen.GARAGE);
                    case 1 -> push(Screen.DEALER);
                    case 2 -> push(Screen.CLUBS);
                    case 3 -> push(Screen.ZONES);
                    case 4, 5 -> {
                        open = false;
                        host.travelTo(selection == 4 ? Shops.Kind.TUNING : Shops.Kind.STYLING);
                    }
                    default -> {
                        open = false;
                        session.save();
                    }
                }
            }
            case GARAGE -> {
                if (selection != profile().selectedIndex()) {
                    garage().select(selection);
                    host.carChanged();
                    toast(carName(profile().selectedCar().carId()) + " 탑승");
                }
            }
            case DEALER -> {
                List<CarCatalog.Entry> items = dealerItems();
                if (selection < items.size()) {
                    report(garage().buyCar(items.get(selection).spec().id()), true);
                }
            }
            case CLUBS -> {
                selectedClub = session.clubs().get(selection);
                push(Screen.EVENTS);
            }
            case EVENTS -> {
                ClubEvent e = selectedClub.events().get(selection);
                Garage.Result can = garage().canEnter(selectedClub);
                if (!can.ok()) {
                    toast(can.message());
                } else {
                    open = false;
                    host.startEvent(selectedClub, e);
                }
            }
            case ZONES -> {
                open = false;
                session.save();
                host.travelZone(driftengine.world.map.Zone.values()[selection]);
            }
        }
    }

    private void report(Garage.Result r, boolean carChanged) {
        toast(r.message());
        if (r.ok()) {
            session.save();
            if (carChanged) {
                host.carChanged();
            }
        }
    }

    private List<CarCatalog.Entry> dealerItems() {
        List<CarCatalog.Entry> list = new ArrayList<>(session.market().dealerList(session.catalog()));
        for (CarCatalog.Entry e : session.catalog()) {
            if (profile().unlocked().contains(e.spec().id()) && !list.contains(e)) {
                list.add(e);
            }
        }
        return list;
    }

    private String carName(String id) {
        CarCatalog.Entry e = garage().entry(id);
        return e.spec().brand() + " " + e.spec().name();
    }

    static String trackName(String id) {
        return switch (id) {
            case "drift-pad" -> "드리프트 연습장";
            case "taebaek-pass" -> "태백 고개";
            case "yongin-circuit" -> "용인 서킷";
            case "city-streets" -> "도시";
            default -> id;
        };
    }

    // ------------------------------------------------------------------ 그리기

    void draw(UiCanvas c, List<Marker> markers, boolean inShop) {
        if (!inShop) {
            if (open) {
                drawMenu(c);
            } else {
                drawMarkers(c, markers);
                drawHud(c);
            }
        }
        drawToasts(c);
    }

    private void drawMarkers(UiCanvas c, List<Marker> markers) {
        float s = c.scale();
        for (Marker m : markers) {
            if (m.prompt()) {
                float w = 380 * s;
                float x = (c.width() - w) / 2;
                float y = c.height() - 210 * s;
                Theme.panel(c, x, y, w, 64 * s, 14 * s);
                c.rect(x + 14 * s, y + 12 * s, 4 * s, 40 * s, m.kind().ui);
                c.text(x + 32 * s, y + 10 * s, 13 * s, m.kind().title, m.kind().ui, Align.LEFT);
                c.text(x + 32 * s, y + 30 * s, 20 * s, m.kind().label + " 들어가기", Theme.TEXT, Align.LEFT);
                Theme.key(c, x + w - 98 * s, y + 20 * s, "Enter", "");
                continue;
            }
            if (m.distance() < 18) {
                continue;
            }
            float pulse = 0.75f + 0.25f * (float) Math.sin(time * 3);
            c.glow(m.x(), m.y(), 26 * s, UiCanvas.alpha(m.kind().ui, 0.35f * pulse));
            c.circle(m.x(), m.y(), 9 * s, 0x0B0F16E0);
            c.circle(m.x(), m.y(), 6 * s, m.kind().ui);
            String t = m.kind().label + "  " + Math.round(m.distance()) + "m";
            Theme.pill(c, m.x(), m.y() - 40 * s, 13 * s, t, 0x0B0F16CC, Theme.TEXT, Align.CENTER);
        }
    }

    private void drawHud(UiCanvas c) {
        float s = c.scale();
        float w = c.width();
        float h = c.height();
        Car car = host.car();
        CarSpec spec = car.spec();
        int rating = PerformanceRating.of(spec);
        CarClass cls = session.market().classes().classOf(rating);

        // 왼쪽 위: 클래스 배지 + 차 이름 + 성능
        c.glow(60 * s, 40 * s, 300 * s, 0x05080D80);
        Theme.classBadge(c, 22 * s, 20 * s, 44 * s, cls.label());
        c.shadowText(80 * s, 16 * s, 13 * s, spec.brand().toUpperCase(), Theme.DIM, Align.LEFT);
        c.shadowText(80 * s, 32 * s, 24 * s, spec.name(), Theme.TEXT, Align.LEFT);
        c.shadowText(82 * s + c.textWidth(spec.name(), 24 * s) + 12 * s, 38 * s, 15 * s, "성능 " + rating, Theme.ACCENT, Align.LEFT);
        Theme.pill(c, 80 * s, 66 * s, 12 * s, host.zone().label, 0x0B0F16B3, Theme.DIM, Align.LEFT);

        // 오른쪽 위: 돈 + 드리프트 총점
        Theme.pill(c, w - 22 * s, 20 * s, 19 * s, Theme.money(profile().money()), 0x0B0F16CC, Theme.TEXT, Align.RIGHT);
        c.shadowText(w - 30 * s, 58 * s, 14 * s, String.format("드리프트 총점 %,d", host.scorer().total()), Theme.DIM, Align.RIGHT);

        drawGauge(c, car, w - 170 * s, h - 160 * s, 128 * s);
        drawDrift(c, car);

        EventRun run = host.eventRun();
        if (run != null) {
            drawEvent(c, run);
        } else if (host.practice() != null) {
            drawPractice(c, host.practice());
        }
        if (!open) {
            float kx = 22 * s;
            float ky = h - 44 * s;
            kx = Theme.key(c, kx, ky, "Tab", "메뉴");
            kx = Theme.key(c, kx, ky, "X", "트랙션 컨트롤");
            if (car.spec().nitroCapacity() > 0) {
                kx = Theme.key(c, kx, ky, "Shift", "니트로");
            }
            kx = Theme.key(c, kx, ky, "T", "차 세우기");
            Theme.key(c, kx, ky, "C", "카메라");
        }
    }

    /** 원형 회전계 + 가운데 속도 + 기어 + 부스트 고리 + 표시등 */
    private void drawGauge(UiCanvas c, Car car, float cx, float cy, float r) {
        float s = c.scale();
        CarSpec spec = car.spec();
        float redline = Math.max(1000, spec.engine().redlineRpm());
        float maxRpm = (float) Math.ceil(redline / 1000f + 0.5f) * 1000;
        shownRpm += (car.engine().rpm() - shownRpm) * 0.35f;
        float a0 = (float) Math.toRadians(135);
        float sweep = (float) Math.toRadians(270);
        // 바탕
        c.glow(cx, cy + 6 * s, r * 1.25f, 0x000000B0);
        c.circle(cx, cy, r, 0x0A0E15D9);
        c.circle(cx, cy, r * 0.97f, 0x121A26D9);
        // 눈금 띠 (회색) + 레드존 (빨강)
        float redA = a0 + sweep * (redline / maxRpm);
        c.arc(cx, cy, r * 0.80f, r * 0.86f, a0, redA, 0xFFFFFF26);
        c.arc(cx, cy, r * 0.80f, r * 0.86f, redA, a0 + sweep, 0xFF4D5E99);
        // 지금 회전수 (흰 → 주황 → 빨강)
        float frac = Math.max(0, Math.min(1, shownRpm / maxRpm));
        int hot = car.engine().limiter() ? Theme.BAD : Theme.ACCENT;
        c.arc(cx, cy, r * 0.79f, r * 0.89f, a0, a0 + sweep * frac, 0xFFFFFFFF, hot);
        // 눈금 숫자 (천 단위)
        for (int k = 0; k <= maxRpm / 1000; k++) {
            float a = a0 + sweep * (k * 1000 / maxRpm);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            c.line(cx + ca * r * 0.90f, cy + sa * r * 0.90f, cx + ca * r * 0.96f, cy + sa * r * 0.96f, 2 * s,
                    k * 1000 >= redline ? Theme.BAD : 0xFFFFFFB0);
            c.text(cx + ca * r * 0.67f, cy + sa * r * 0.67f - 7 * s, 12 * s, Integer.toString(k), Theme.DIM, Align.CENTER);
        }
        // 부스트 (안쪽 작은 고리, 청록)
        if (car.engine().turbo() != null) {
            float bf = Math.max(0, Math.min(1, car.engine().boost() / Math.max(0.1f, car.engine().turbo().spec().maxBoost())));
            float b0 = (float) Math.toRadians(200);
            float bs = (float) Math.toRadians(140);
            c.arc(cx, cy, r * 0.52f, r * 0.56f, b0, b0 + bs, 0xFFFFFF1A);
            c.arc(cx, cy, r * 0.52f, r * 0.56f, b0, b0 + bs * bf, Theme.CYAN);
            c.text(cx, cy - r * 0.50f, 11 * s, String.format("%.1f bar", car.engine().boost()), Theme.CYAN, Align.CENTER);
        }
        // 속도 + 단위
        String speed = String.format("%.0f", Math.abs(car.forwardSpeed()) * 3.6f);
        c.text(cx, cy - 36 * s, 50 * s, speed, Theme.TEXT, Align.CENTER);
        c.text(cx, cy + 26 * s, 12 * s, "km/h", Theme.DIM, Align.CENTER);
        // 기어 (아래 둥근 상자)
        String gear = car.gearbox().label();
        float gw = 46 * s;
        c.roundRectV(cx - gw / 2, cy + r * 0.48f, gw, 40 * s, 9 * s, car.engine().limiter() ? Theme.BAD : Theme.SELECT_TOP,
                car.engine().limiter() ? 0xC0303CFF : Theme.SELECT_BOTTOM);
        c.text(cx, cy + r * 0.48f + 4 * s, 28 * s, gear, 0x0B0F16FF, Align.CENTER);
        // 니트로 (왼쪽 세로 막대)
        if (spec.nitroCapacity() > 0) {
            float bh = r * 1.3f;
            float bx = cx - r - 26 * s;
            float by = cy - bh / 2;
            float lv = car.nitroLevel();
            int col = 0x8C6BFFFF;
            if (car.nitroActive()) {
                c.glow(bx + 5 * s, by + bh * (1 - lv / 2), 40 * s, 0x8C6BFF90);
            }
            c.roundRect(bx, by, 10 * s, bh, 5 * s, 0x0B0F16CC);
            c.roundRectV(bx + 2 * s, by + 2 * s + (bh - 4 * s) * (1 - lv), 6 * s, Math.max(6 * s, (bh - 4 * s) * lv), 3 * s,
                    0xC2B0FFFF, col);
            c.text(bx + 5 * s, by + bh + 6 * s, 11 * s, "NOS", car.nitroActive() ? 0xC2B0FFFF : Theme.DIM, Align.CENTER);
        }
        // 표시등: AUTO/MANUAL, TC
        TractionControl tc = car.traction();
        boolean blink = tc.active() && (time * 8) % 2 < 1;
        float iy = cy + r + 10 * s;
        Theme.pill(c, cx - 6 * s, iy, 11 * s, car.gearbox().automatic() ? "AUTO" : "MANUAL", 0x0B0F16CC, Theme.DIM, Align.RIGHT);
        int tcBg = tc.mode() == TractionControl.Mode.OFF ? 0x0B0F16CC : (blink ? Theme.ACCENT : 0x0B0F16CC);
        int tcFg = tc.mode() == TractionControl.Mode.OFF ? 0xFFFFFF55 : (blink ? 0x0B0F16FF : Theme.ACCENT);
        Theme.pill(c, cx + 6 * s, iy, 11 * s, "TC " + tc.mode().label(), tcBg, tcFg, Align.LEFT);
    }

    /** 가운데 위: 드리프트 점수 + 배율 + 확정까지 남은 시간 막대 */
    private void drawDrift(UiCanvas c, Car car) {
        float s = c.scale();
        float w = c.width();
        DriftScorer sc = host.scorer();
        if (open) {
            return;
        }
        if (sc.chainPoints() > 0) {
            float cx = w / 2;
            float y = 64 * s;
            String pts = String.format("%,d", Math.round(sc.chainPoints()));
            c.glow(cx, y + 30 * s, 160 * s, 0xFFB32122);
            c.shadowText(cx, y, 54 * s, pts, Theme.TEXT, Align.CENTER);
            // 배율 알약 + 다음 배율까지 진행
            float pw = Theme.pill(c, cx, y + 66 * s, 17 * s, String.format("x%.1f", sc.multiplier()), Theme.ACCENT, 0x0B0F16FF, Align.CENTER);
            float bw = 220 * s;
            float bank = sc.bankProgress();
            if (bank > 0) {
                // 확정 대기: 막대가 줄어듦 (다시 미끄러지면 이어짐)
                Theme.bar(c, cx - bw / 2, y + 100 * s, bw, 5 * s, 1 - bank, 1 - bank, Theme.CYAN);
                c.shadowText(cx, y + 110 * s, 12 * s, "확정까지", Theme.CYAN, Align.CENTER);
            } else {
                Theme.bar(c, cx - bw / 2, y + 100 * s, bw, 5 * s, sc.multiplierProgress(), sc.multiplierProgress(), Theme.ACCENT);
                float angle = (float) Math.toDegrees(car.driftAngle());
                c.shadowText(cx, y + 110 * s, 13 * s, String.format("%.0f°", angle), Theme.DIM, Align.CENTER);
            }
            if (pw < 0) {
                throw new IllegalStateException();
            }
        } else if (bankFlash > 0) {
            float a = Math.min(1, bankFlash);
            c.shadowText(w / 2, 80 * s, 40 * s, String.format("+%,d", bankFlashPoints), UiCanvas.alpha(Theme.GOOD, a), Align.CENTER);
        }
    }

    /** 서킷 자유 주행: 오른쪽 위 랩 시간 */
    private void drawPractice(UiCanvas c, LapTimer lap) {
        float s = c.scale();
        float pw = 230 * s;
        float x = c.width() - 22 * s - pw;
        float y = 84 * s;
        Theme.panel(c, x, y, pw, 86 * s, 12 * s);
        c.text(x + 16 * s, y + 10 * s, 12 * s, lap.running() ? "랩 " + (lap.laps() + 1) + "  ·  " + lap.passed() + "/" + lap.total()
                : "출발선을 지나면 시작", Theme.DIM, Align.LEFT);
        c.text(x + 16 * s, y + 28 * s, 26 * s, TerrainScene.lapText(lap.time()), lap.running() ? Theme.TEXT : Theme.DIM, Align.LEFT);
        String best = lap.best() > 0 ? "최고 " + TerrainScene.lapText(lap.best()) : "최고 —";
        c.text(x + 16 * s, y + 62 * s, 12 * s, best, Theme.ACCENT, Align.LEFT);
        if (lap.last() > 0) {
            c.text(x + pw - 16 * s, y + 62 * s, 12 * s, "지난 " + TerrainScene.lapText(lap.last()), Theme.DIM, Align.RIGHT);
        }
    }

    private void drawEvent(UiCanvas c, EventRun run) {
        float s = c.scale();
        float w = c.width();
        float h = c.height();
        Theme.pill(c, w / 2, 14 * s, 14 * s, run.clubName + "  ·  " + run.event.title(), 0x0B0F16CC, Theme.DIM, Align.CENTER);
        switch (run.phase) {
            case COUNTDOWN -> {
                String n = Integer.toString((int) Math.ceil(run.timer));
                c.glow(w / 2, h / 2 - 20 * s, 140 * s, 0xFFB32140);
                c.shadowText(w / 2, h / 2 - 80 * s, 110 * s, n, Theme.TEXT, Align.CENTER);
            }
            case RUNNING -> {
                if (run.mode == EventRun.Mode.LAP) {
                    drawLapRun(c, run);
                    break;
                }
                if (run.mode == EventRun.Mode.COURSE) {
                    drawCourseRun(c, run);
                    break;
                }
                int t = (int) Math.ceil(run.timer);
                long now = host.scorer().totalWithChain();
                float pw = 300 * s;
                float x = w / 2 + 230 * s;
                Theme.panel(c, x, 14 * s, pw * 0.62f, 64 * s, 12 * s);
                c.text(x + 16 * s, 22 * s, 12 * s, "남은 시간", Theme.DIM, Align.LEFT);
                c.text(x + 16 * s, 38 * s, 26 * s, String.format("%d:%02d", t / 60, t % 60), run.timer < 10 ? Theme.BAD : Theme.TEXT, Align.LEFT);
                float goal = run.event.target();
                Theme.bar(c, x + 100 * s, 50 * s, pw * 0.62f - 116 * s, 7 * s, now / goal, now / goal, now >= goal ? Theme.GOOD : Theme.ACCENT);
                c.text(x + pw * 0.62f - 16 * s, 24 * s, 13 * s, String.format("%,d / %,d", now, (long) goal), now >= goal ? Theme.GOOD : Theme.TEXT, Align.RIGHT);
            }
            case RESULT -> {
                float pw = 480 * s;
                float ph = 240 * s;
                float x = (w - pw) / 2;
                float y = (h - ph) / 2;
                Theme.panel(c, x, y, pw, ph, 18 * s);
                c.rect(x + 18 * s, y, pw - 36 * s, 3 * s, Theme.ACCENT);
                c.text(w / 2, y + 20 * s, 22 * s, run.event.title() + " 결과", Theme.TEXT, Align.CENTER);
                String res = run.scoreEvent() ? String.format("%,d", (long) run.result)
                        : (run.result > 0 ? TerrainScene.lapText(run.result) : "—");
                c.text(w / 2, y + 58 * s, 52 * s, res, Theme.TEXT, Align.CENTER);
                for (int i = 0; i < 3; i++) {
                    float sx = w / 2 + (i - 1) * 46 * s;
                    boolean got = i < run.stars;
                    if (got) {
                        c.glow(sx, y + 150 * s, 30 * s, 0xFFB32166);
                    }
                    c.text(sx, y + 132 * s, 36 * s, "★", got ? Theme.ACCENT : 0xFFFFFF30, Align.CENTER);
                }
                c.text(w / 2, y + 190 * s, 17 * s, run.message, run.stars > 0 ? Theme.GOOD : Theme.BAD, Align.CENTER);
            }
            default -> {
            }
        }
    }

    /** 랩타임 이벤트: 랩 시간, 목표, 체크포인트 */
    private void drawLapRun(UiCanvas c, EventRun run) {
        float s = c.scale();
        float pw = 250 * s;
        float x = c.width() / 2 + 190 * s;
        LapTimer lap = run.gates;
        float goal = run.event.target();
        Theme.panel(c, x, 14 * s, pw, 78 * s, 12 * s);
        c.text(x + 16 * s, 22 * s, 12 * s, lap.running() ? "랩 시간" : "출발선을 지나면 시작", lap.running() ? Theme.DIM : Theme.ACCENT, Align.LEFT);
        if (lap.running()) {
            c.text(x + pw - 16 * s, 22 * s, 12 * s, "체크 " + lap.passed() + "/" + lap.total(), Theme.DIM, Align.RIGHT);
        }
        float t = lap.time();
        c.text(x + 16 * s, 38 * s, 28 * s, TerrainScene.lapText(t), t > goal ? Theme.BAD : Theme.TEXT, Align.LEFT);
        c.text(x + pw - 16 * s, 40 * s, 12 * s, "목표 " + TerrainScene.lapText(goal), Theme.TEXT, Align.RIGHT);
        c.text(x + pw - 16 * s, 56 * s, 12 * s, "★★★ " + TerrainScene.lapText(goal * 0.94f), Theme.ACCENT, Align.RIGHT);
        Theme.bar(c, x + 16 * s, 76 * s, pw - 32 * s, 5 * s, lap.passed() / (float) lap.total(), lap.passed() / (float) lap.total(), Theme.CYAN);
    }

    /** 고갯길 드리프트: 점수, 진행, 지난 시간 */
    private void drawCourseRun(UiCanvas c, EventRun run) {
        float s = c.scale();
        float pw = 250 * s;
        float x = c.width() / 2 + 190 * s;
        LapTimer lap = run.gates;
        long now = host.scorer().totalWithChain();
        float goal = run.event.target();
        Theme.panel(c, x, 14 * s, pw, 78 * s, 12 * s);
        int el = (int) run.timer;
        int left = (int) Math.max(0, EventRun.COURSE_LIMIT - run.timer);
        c.text(x + 16 * s, 22 * s, 12 * s, "결승선까지  ·  " + String.format("%d:%02d", el / 60, el % 60)
                + "  (남은 " + String.format("%d:%02d", left / 60, left % 60) + ")", Theme.DIM, Align.LEFT);
        c.text(x + 16 * s, 38 * s, 26 * s, String.format("%,d", now), now >= goal ? Theme.GOOD : Theme.TEXT, Align.LEFT);
        c.text(x + pw - 16 * s, 44 * s, 13 * s, String.format("목표 %,d", (long) goal), Theme.TEXT, Align.RIGHT);
        float prog = lap.passed() / (float) lap.total();
        Theme.bar(c, x + 16 * s, 76 * s, pw - 32 * s, 5 * s, prog, prog, Theme.CYAN);
    }

    private void drawToasts(UiCanvas c) {
        float s = c.scale();
        float y = 112 * s;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            String t = toasts.get(i);
            float life = toastTimes.get(i);
            float a = Math.min(1, life / 0.4f);
            float tw = c.textWidth(t, 16 * s) + 44 * s;
            float x = (c.width() - tw) / 2;
            c.shadow(x, y, tw, 36 * s, 10 * s, UiCanvas.alpha(0x00000080, a));
            c.roundRect(x, y, tw, 36 * s, 18 * s, UiCanvas.alpha(0x0B0F16E6, a));
            c.circle(x + 18 * s, y + 18 * s, 4 * s, UiCanvas.alpha(Theme.ACCENT, a));
            c.text(x + 30 * s, y + 8 * s, 16 * s, t, UiCanvas.alpha(Theme.TEXT, a), Align.LEFT);
            y += 42 * s;
        }
    }

    // ------------------------------------------------------------------ Tab 메뉴

    private void drawMenu(UiCanvas c) {
        float s = c.scale();
        float w = c.width();
        float h = c.height();
        c.rect(0, 0, w, h, 0x05080D99);
        c.rectH(0, 0, w * 0.5f, h, 0x05080DCC, 0x05080D00);
        Screen screen = stack.isEmpty() ? Screen.MAIN : stack.peek();
        Theme.slantTitle(c, 36 * s, 26 * s, "MENU", "Tab 닫기  ·  Backspace 뒤로", Theme.ACCENT);
        Theme.pill(c, w - 36 * s, 34 * s, 20 * s, Theme.money(profile().money()), 0x0B0F16D9, Theme.TEXT, Align.RIGHT);

        // 왼쪽: 큰 메뉴 (하위 화면이면 흐리게)
        float lx = 36 * s;
        float ly = 110 * s;
        int mainSel = screen == Screen.MAIN ? selection : (selections.isEmpty() ? 0 : selections.peekLast());
        for (int i = 0; i < MAIN_ITEMS.length; i++) {
            float ry = ly + i * 66 * s;
            boolean sel = i == mainSel;
            boolean active = screen == Screen.MAIN;
            if (sel) {
                c.shadow(lx, ry, 360 * s, 58 * s, 12 * s, 0x00000060);
                c.slant(lx, ry, 360 * s, 58 * s, 12 * s, active ? Theme.ACCENT : 0xFFFFFF30, active ? Theme.ACCENT_DEEP : 0xFFFFFF18);
            } else {
                c.slant(lx, ry, 360 * s, 58 * s, 12 * s, 0x0B0F16B3, 0x0B0F1666);
            }
            int fg = sel && active ? 0x0B0F16FF : Theme.TEXT;
            c.text(lx + 26 * s, ry + 8 * s, 21 * s, MAIN_ITEMS[i], fg, Align.LEFT);
            c.text(lx + 26 * s, ry + 35 * s, 12 * s, MAIN_HINTS[i], sel && active ? 0x0B0F16B0 : Theme.DIM, Align.LEFT);
        }
        if (screen == Screen.MAIN) {
            drawMainSide(c, w - 36 * s - 420 * s, 110 * s, 420 * s);
        } else {
            drawList(c, screen, 430 * s, 110 * s, w - 466 * s, h - 200 * s);
        }
        float kx = 36 * s;
        float ky = h - 52 * s;
        kx = Theme.key(c, kx, ky, "↑↓", "고르기");
        kx = Theme.key(c, kx, ky, "Enter", "선택");
        Theme.key(c, kx, ky, "Backspace", "뒤로");
    }

    /** 메인 메뉴 오른쪽: 지금 차 카드 */
    private void drawMainSide(UiCanvas c, float x, float y, float w) {
        float s = c.scale();
        Profile.OwnedCar oc = profile().selectedCar();
        CarSpec spec = garage().currentSpec();
        PerformanceRating.Breakdown b = PerformanceRating.breakdown(spec);
        CarClass cls = session.market().classes().classOf(b.total());
        float h = 330 * s;
        Theme.panel(c, x, y, w, h, 16 * s);
        c.text(x + 22 * s, y + 18 * s, 13 * s, "지금 타는 차", Theme.DIM, Align.LEFT);
        c.text(x + 22 * s, y + 38 * s, 28 * s, spec.brand() + " " + spec.name(), Theme.TEXT, Align.LEFT);
        Theme.classBadge(c, x + 22 * s, y + 88 * s, 40 * s, cls.label());
        c.text(x + 80 * s, y + 84 * s, 36 * s, Integer.toString(b.total()), Theme.TEXT, Align.LEFT);
        c.text(x + 80 * s + c.textWidth(Integer.toString(b.total()), 36 * s) + 10 * s, y + 100 * s, 14 * s, "성능", Theme.DIM, Align.LEFT);
        float sy = y + 150 * s;
        String[][] rows = {{"출력", String.format("%.0f PS", spec.peakPowerKw() * 1.36f)},
                {"무게", String.format("%.0f kg", spec.mass())}, {"구동", spec.layout().name()},
                {"최고 속도", String.format("%.0f km/h", b.topSpeedKmh())}};
        for (String[] r : rows) {
            c.text(x + 22 * s, sy, 15 * s, r[0], Theme.DIM, Align.LEFT);
            c.text(x + w - 22 * s, sy, 15 * s, r[1], Theme.TEXT, Align.RIGHT);
            sy += 28 * s;
        }
        int parts = 0;
        for (driftengine.game.Part p : driftengine.game.Part.values()) {
            parts += oc.tuning().level(p);
        }
        c.rect(x + 22 * s, sy + 4 * s, w - 44 * s, Math.max(1, s), Theme.LINE);
        c.text(x + 22 * s, sy + 16 * s, 14 * s, "튜닝 단계 합 " + parts + "  ·  별 " + profile().totalStars() + "개", Theme.DIM, Align.LEFT);
    }

    private void drawList(UiCanvas c, Screen screen, float x, float y, float w, float h) {
        float s = c.scale();
        Theme.panel(c, x, y, w, h, 16 * s);
        String title = switch (screen) {
            case GARAGE -> "내 차고";
            case DEALER -> "딜러쉽  ·  클래스 4 까지";
            case CLUBS -> "클럽";
            case EVENTS -> selectedClub.name();
            case ZONES -> "지역 이동  ·  지금 " + host.zone().label;
            default -> "";
        };
        c.text(x + 24 * s, y + 18 * s, 24 * s, title, Theme.TEXT, Align.LEFT);
        c.rect(x + 24 * s, y + 56 * s, w - 48 * s, Math.max(1, s), Theme.LINE);
        List<String[]> rows = rows(screen);
        float rowH = 64 * s;
        int visible = (int) ((h - 80 * s) / rowH);
        int first = Math.max(0, Math.min(selection - visible / 2, rows.size() - visible));
        for (int i = first; i < Math.min(rows.size(), first + visible); i++) {
            float ry = y + 68 * s + (i - first) * rowH;
            boolean sel = i == selection;
            Theme.row(c, x + 14 * s, ry, w - 28 * s, rowH - 8 * s, 12 * s, sel);
            String[] r = rows.get(i);
            int fg = sel ? 0x0B0F16FF : Theme.TEXT;
            int fg2 = sel ? 0x0B0F16B0 : Theme.DIM;
            float tx = x + 32 * s;
            if (r.length > 3 && !r[3].isEmpty()) {
                Theme.classBadge(c, tx, ry + 11 * s, 34 * s, r[3]);
                tx += 50 * s;
            }
            c.text(tx, ry + 8 * s, 19 * s, r[0], fg, Align.LEFT);
            if (r.length > 1) {
                c.text(tx, ry + 33 * s, 13 * s, r[1], fg2, Align.LEFT);
            }
            if (r.length > 2) {
                c.text(x + w - 32 * s, ry + 17 * s, 17 * s, r[2], sel ? 0x0B0F16FF : Theme.ACCENT, Align.RIGHT);
            }
        }
    }

    /** 화면별 줄: {이름, 설명, 오른쪽 값, 클래스 배지} */
    private List<String[]> rows(Screen screen) {
        List<String[]> out = new ArrayList<>();
        switch (screen) {
            case GARAGE -> {
                List<Profile.OwnedCar> cars = profile().cars();
                for (int i = 0; i < cars.size(); i++) {
                    Profile.OwnedCar oc = cars.get(i);
                    CarSpec spec = Tuning.apply(garage().entry(oc.carId()).spec(), oc.tuning());
                    int r = PerformanceRating.of(spec);
                    out.add(new String[]{carName(oc.carId()), "성능 " + r + "  ·  " + Math.round(spec.peakPowerKw() * 1.36f) + " PS  ·  "
                            + Math.round(spec.mass()) + " kg", i == profile().selectedIndex() ? "타는 중" : "",
                            session.market().classes().classOf(r).label()});
                }
            }
            case DEALER -> {
                for (CarCatalog.Entry e : dealerItems()) {
                    boolean reward = !session.market().soldAtDealer(e.spec());
                    long owned = profile().cars().stream().filter(o -> o.carId().equals(e.spec().id())).count();
                    out.add(new String[]{e.spec().brand() + " " + e.spec().name() + (owned > 0 ? "  (보유 " + owned + ")" : ""),
                            e.spec().origin() + "  ·  " + Math.round(e.spec().peakPowerKw() * 1.36f) + " PS  ·  " + e.spec().layout().name(),
                            reward ? "클럽 보상 (무료)" : Theme.money(session.market().carPrice(e.spec())),
                            session.market().stockClass(e.spec()).label()});
                }
            }
            case CLUBS -> {
                for (Club club : session.clubs()) {
                    int got = 0;
                    for (ClubEvent e : club.events()) {
                        got += profile().stars(e.id());
                    }
                    out.add(new String[]{club.name(), (club.kind() == Club.Kind.DRIFT ? "드리프트" : "레이싱 (서킷)")
                            + "  ·  클래스 " + club.maxClass().label() + " 이하", "★ " + got + " / " + club.events().size() * 3,
                            club.maxClass().label()});
                }
            }
            case EVENTS -> {
                for (ClubEvent e : selectedClub.events()) {
                    int st = profile().stars(e.id());
                    String goal = e.type() == ClubEvent.Type.DRIFT_SCORE ? String.format("%,d 점", (long) e.target())
                            : String.format("%.1f 초", e.target());
                    String unlock = e.unlockCar() != null ? "  ·  보상 차 " + garage().entry(e.unlockCar()).spec().name() : "";
                    out.add(new String[]{(e.boss() ? "[보스] " : "") + e.title(), trackName(e.track()) + "  ·  목표 " + goal + unlock,
                            "★".repeat(st) + "☆".repeat(3 - st) + "  " + Theme.money(e.reward()), ""});
                }
            }
            case ZONES -> {
                driftengine.world.map.Zone[] zones = driftengine.world.map.Zone.values();
                for (int i = 0; i < zones.length; i++) {
                    out.add(new String[]{zones[i].label, ZONE_HINTS[i], zones[i] == host.zone() ? "지금 여기" : "", ""});
                }
            }
            default -> {
            }
        }
        return out;
    }
}
