package driftengine.world;

import driftengine.game.CarClass;
import driftengine.game.GameSession;
import driftengine.game.Garage;
import driftengine.game.Part;
import driftengine.game.PerformanceRating;
import driftengine.game.Profile;
import driftengine.game.Styling;
import driftengine.game.Tuning;
import driftengine.game.TuningState;
import driftengine.ui.Theme;
import driftengine.ui.UiCanvas;
import driftengine.ui.UiCanvas.Align;
import driftengine.ui.UiInput;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarModels;
import driftengine.vehicle.visual.CarStyle;
import driftengine.vehicle.visual.Livery;

import java.util.ArrayList;
import java.util.List;

/**
 * 샵 안 화면 (CarX Street 방식): 왼쪽 분류 → 가운데 항목 → 오른쪽 성능/요약, 가운데에 차 (카메라가 돎).
 * <p>
 * 조작: ↑↓ 고르기, → 또는 Enter 로 분류 안으로, ← / Backspace 로 나오기.
 * 튜닝샵: 항목에서 Enter = 다음 단계 구입. 스타일링샵: 항목에서 ←→ = 바꿔 보기(차에 바로 입혀 봄), Enter = 바꾼 것 모두 구입.
 * 분류에서 Backspace = 샵 나가기 (사지 않은 스타일은 원래대로).
 */
public final class ShopUi {

    /** 샵이 장면에 바라는 것 */
    public interface Host {
        /** 튜닝을 샀음 → 차 제원·모델 다시 */
        void carChanged();

        /** 스타일 미리보기 (모델만 바꿈, 저장 안 함) */
        void preview(CarStyle style);

        /** 샵 나가기 */
        void exit();

        void toast(String message);
    }

    /** 카메라가 볼 곳 (차 앞 기준 각도·거리·높이) */
    public record Focus(float yawDeg, float distance, float height) {
    }

    private static final String[] STYLE_CATS = {"바디킷", "페인팅", "꾸미기"};
    private static final String[] STYLE_HINTS = {"범퍼 · 스커트 · 스포일러 · 펜더", "색과 마감", "휠 · 캘리퍼 · 틴트"};
    private static final String[][] STYLE_ROWS = {
            {"앞범퍼", "사이드 스커트", "뒤 디퓨저", "스포일러", "펜더"},
            {"차체 색", "도장 마감"},
            {"휠 디자인", "휠 색", "캘리퍼 색", "유리 틴트"}};
    private static final String[][] KIT_NAMES = {{"순정", "립 스포일러", "에어로 (립 + 카나드)"}, {"없음", "사이드 스커트"},
            {"순정", "디퓨저"}, {"없음", "덕테일", "GT 윙"}, {"순정", "와이드 펜더"}};

    private final GameSession session;
    private final Shops.Kind kind;
    private final Host host;
    private int category;
    private int item;
    private boolean inItems;
    private float time;
    // 스타일링: 바꿔 보는 중인 값
    private BodyKit pendingKit;
    private Livery pendingLivery;
    private final List<String> rimDesigns = new ArrayList<>();

    public ShopUi(GameSession session, Shops.Kind kind, Host host) {
        this.session = session;
        this.kind = kind;
        this.host = host;
        Profile.OwnedCar c = session.profile().selectedCar();
        pendingKit = c.tuning().kit();
        pendingLivery = c.livery();
        rimDesigns.add(null);
        for (CarCatalog.Entry e : session.catalog()) {
            if (!e.spec().id().equals(c.carId()) && CarModels.available(e.spec().id())) {
                rimDesigns.add(e.spec().id());
            }
        }
    }

    public Shops.Kind kind() {
        return kind;
    }

    private Garage garage() {
        return session.garage();
    }

    private Profile.OwnedCar car() {
        return session.profile().selectedCar();
    }

    private int categoryCount() {
        return kind == Shops.Kind.TUNING ? Part.Category.values().length : STYLE_CATS.length;
    }

    private int itemCount() {
        return kind == Shops.Kind.TUNING ? tuneRows().size() : STYLE_ROWS[category].length;
    }

    /** 직접 맞추는 값 (캠버 3단계, 휠 오프셋을 산 뒤 하체 목록에 나타남) */
    enum Adjust {
        CAMBER_FRONT("앞 캠버", Part.CAMBER), CAMBER_REAR("뒤 캠버", Part.CAMBER),
        OFFSET_FRONT("앞 오프셋", Part.OFFSET), OFFSET_REAR("뒤 오프셋", Part.OFFSET);

        final String label;
        final Part part;

        Adjust(String label, Part part) {
            this.label = label;
            this.part = part;
        }
    }

    /** 튜닝 목록 한 줄: 부품 또는 맞추는 값 */
    record TuneRow(Part part, Adjust adjust) {
    }

    private List<TuneRow> tuneRows() {
        List<TuneRow> out = new ArrayList<>();
        for (Part p : Part.Category.values()[category].parts()) {
            out.add(new TuneRow(p, null));
            boolean open = p == Part.CAMBER ? car().tuning().level(Part.CAMBER) >= Part.CAMBER.maxLevel()
                    : p == Part.OFFSET && car().tuning().level(Part.OFFSET) >= 1;
            if (open) {
                for (Adjust a : Adjust.values()) {
                    if (a.part == p) {
                        out.add(new TuneRow(null, a));
                    }
                }
            }
        }
        return out;
    }

    private float adjustValue(Adjust a) {
        driftengine.game.Setup st = car().tuning().setup();
        return switch (a) {
            case CAMBER_FRONT -> st.camberFront();
            case CAMBER_REAR -> st.camberRear();
            case OFFSET_FRONT -> st.offsetFront();
            case OFFSET_REAR -> st.offsetRear();
        };
    }

    /** ←→ 로 값 한 칸 (캠버는 왼쪽 = 더 눕힘) */
    private void adjust(Adjust a, int dir) {
        driftengine.game.Setup st = car().tuning().setup();
        driftengine.game.Setup next = switch (a) {
            case CAMBER_FRONT -> st.withCamberFront(st.camberFront() + dir * driftengine.game.Setup.CAMBER_STEP);
            case CAMBER_REAR -> st.withCamberRear(st.camberRear() + dir * driftengine.game.Setup.CAMBER_STEP);
            case OFFSET_FRONT -> st.withOffsetFront(st.offsetFront() + dir * driftengine.game.Setup.OFFSET_STEP);
            case OFFSET_REAR -> st.withOffsetRear(st.offsetRear() + dir * driftengine.game.Setup.OFFSET_STEP);
        };
        if (!next.equals(st)) {
            garage().setSetup(next);
            host.carChanged();
        }
    }

    public void update(float dt) {
        time += dt;
    }

    // ------------------------------------------------------------------ 입력

    public void input(UiInput in) {
        if (!inItems) {
            if (in.up()) {
                category = (category + categoryCount() - 1) % categoryCount();
            }
            if (in.down()) {
                category = (category + 1) % categoryCount();
            }
            if (in.right() || in.confirm()) {
                inItems = true;
                item = 0;
            }
            if (in.back() || in.menu()) {
                leave();
            }
            return;
        }
        if (in.up()) {
            item = (item + itemCount() - 1) % itemCount();
        }
        if (in.down()) {
            item = (item + 1) % itemCount();
        }
        if (kind == Shops.Kind.TUNING) {
            List<TuneRow> rows = tuneRows();
            item = Math.min(item, rows.size() - 1);
            TuneRow row = rows.get(item);
            if (row.adjust() != null && (in.left() || in.right())) {
                adjust(row.adjust(), in.right() ? 1 : -1);
            } else if (in.left() && row.part() != null && car().tuning().level(row.part()) > 0) {
                downgradePart(row.part());
            } else if (in.left() || in.back()) {
                inItems = false;
                downPart = null;
            } else if (in.confirm()) {
                if (row.part() != null) {
                    buyPart(row.part());
                } else {
                    host.toast("←→ 로 값을 맞춥니다 (무료)");
                }
            }
        } else {
            if (in.left()) {
                changeStyle(-1);
            } else if (in.right()) {
                changeStyle(1);
            } else if (in.confirm()) {
                buyStyle();
            } else if (in.back()) {
                inItems = false;
            }
        }
        if (in.menu()) {
            leave();
        }
    }

    private void leave() {
        if (kind == Shops.Kind.STYLING && pendingCost() > 0) {
            host.toast("사지 않은 스타일은 원래대로 돌아갑니다");
        }
        pendingKit = car().tuning().kit();
        pendingLivery = car().livery();
        host.preview(garage().currentStyle());
        session.save();
        host.exit();
    }

    /** 내리기 확인 대기 (← 를 두 번 눌러야 내림: 분류로 돌아가려다 실수로 내리지 않게) */
    private Part downPart;
    private float downUntil;
    static final float DOWN_CONFIRM = 2.5f;

    private void downgradePart(Part p) {
        if (p != downPart || time > downUntil) {
            downPart = p;
            downUntil = time + DOWN_CONFIRM;
            int lv = car().tuning().level(p);
            host.toast("← 한 번 더: " + p.label() + " " + (lv - 1 == 0 ? "순정" : (lv - 1) + "단계") + "로 내리기 (+"
                    + garage().downgradeRefund(p) + " CR 돌려받음)");
            return;
        }
        downPart = null;
        Garage.Result r = garage().downgrade(p);
        host.toast(r.message());
        if (r.ok()) {
            session.save();
            host.carChanged();
        }
    }

    private void buyPart(Part p) {
        Garage.Result r = garage().upgrade(p);
        host.toast(r.message());
        if (r.ok()) {
            session.save();
            host.carChanged();
        }
    }

    // ------------------------------------------------------------------ 스타일링

    /** 지금 행의 선택지 개수와 현재 번호 */
    private int[] option() {
        BodyKit k = pendingKit;
        Livery l = pendingLivery;
        return switch (category) {
            case 0 -> {
                int[] v = {k.front(), k.side(), k.rear(), k.spoiler(), k.widebody()};
                yield new int[]{BodyKit.MAX[item] + 1, v[item]};
            }
            case 1 -> item == 0 ? new int[]{Livery.PAINTS.size() + 1, l.paint() + 1} : new int[]{Livery.FINISHES.size() + 1, l.finish() + 1};
            default -> switch (item) {
                case 0 -> new int[]{rimDesigns.size(), Math.max(0, rimDesigns.indexOf(l.rimFrom()))};
                case 1 -> new int[]{Livery.RIM_COLORS.size() + 1, l.rimColor() + 1};
                case 2 -> new int[]{Livery.CALIPERS.size() + 1, l.caliper() + 1};
                default -> new int[]{Livery.TINTS.size(), l.tint()};
            };
        };
    }

    private void changeStyle(int dir) {
        int[] o = option();
        int next = Math.floorMod(o[1] + dir, o[0]);
        if (category == 0) {
            int[] v = {pendingKit.front(), pendingKit.side(), pendingKit.rear(), pendingKit.spoiler(), pendingKit.widebody()};
            v[item] = next;
            pendingKit = new BodyKit(v[0], v[1], v[2], v[3], v[4]);
        } else if (category == 1) {
            pendingLivery = item == 0 ? pendingLivery.withPaint(next - 1) : pendingLivery.withFinish(next - 1);
        } else {
            pendingLivery = switch (item) {
                case 0 -> pendingLivery.withRimFrom(rimDesigns.get(next));
                case 1 -> pendingLivery.withRimColor(next - 1);
                case 2 -> pendingLivery.withCaliper(next - 1);
                default -> pendingLivery.withTint(next);
            };
        }
        host.preview(garage().styleOf(car().carId(), pendingKit, pendingLivery));
    }

    private int kitCost() {
        BodyKit now = car().tuning().kit();
        int[] a = {now.front(), now.side(), now.rear(), now.spoiler(), now.widebody()};
        int[] b = {pendingKit.front(), pendingKit.side(), pendingKit.rear(), pendingKit.spoiler(), pendingKit.widebody()};
        int cost = 0;
        for (int i = 0; i < 5; i++) {
            if (b[i] > a[i]) {
                cost += Tuning.kitPrice(b[i]);
            }
        }
        return cost;
    }

    public int pendingCost() {
        return kitCost() + Styling.price(car().livery(), pendingLivery);
    }

    private boolean pendingChanges() {
        return !pendingKit.equals(car().tuning().kit()) || !pendingLivery.equals(car().livery());
    }

    private void buyStyle() {
        if (!pendingChanges()) {
            host.toast("←→ 로 바꿔 본 뒤 Enter 로 구입합니다");
            return;
        }
        int cost = pendingCost();
        if (session.profile().money() < cost) {
            host.toast("돈이 모자랍니다: " + Theme.money(cost) + " 필요");
            return;
        }
        BodyKit now = car().tuning().kit();
        int[] a = {now.front(), now.side(), now.rear(), now.spoiler(), now.widebody()};
        int[] b = {pendingKit.front(), pendingKit.side(), pendingKit.rear(), pendingKit.spoiler(), pendingKit.widebody()};
        for (int i = 0; i < 5; i++) {
            if (a[i] != b[i]) {
                Garage.Result r = garage().setKit(i, b[i]);
                if (!r.ok()) {
                    host.toast(r.message());
                    return;
                }
            }
        }
        if (!pendingLivery.equals(car().livery())) {
            Garage.Result r = garage().setLivery(pendingLivery);
            if (!r.ok()) {
                host.toast(r.message());
                return;
            }
        }
        session.save();
        host.toast("스타일 적용 완료 (" + Theme.money(cost) + ")");
        host.carChanged();
    }

    // ------------------------------------------------------------------ 카메라

    public Focus focus() {
        if (kind == Shops.Kind.TUNING) {
            if (!inItems) {
                return new Focus(35 + time * 4, 6.6f, 1.7f);
            }
            List<TuneRow> rows = tuneRows();
            if (item < rows.size() && rows.get(item).adjust() != null) {
                // 캠버·오프셋: 앞(또는 뒤)에서 낮게 → 바퀴 기울기·튀어나온 정도가 보이게
                boolean rear = rows.get(item).adjust() == Adjust.CAMBER_REAR || rows.get(item).adjust() == Adjust.OFFSET_REAR;
                return new Focus(rear ? 180 : 0, 5.2f, 0.7f);
            }
            return switch (Part.Category.values()[category]) {
                case ENGINE -> new Focus(25, 5.4f, 1.9f);
                case GEAR -> new Focus(95, 5.6f, 1.2f);
                case FUEL -> new Focus(150, 5.6f, 1.5f);
                case AERO -> new Focus(170, 6.4f, 2.6f);
                case WEIGHT -> new Focus(80, 6.4f, 2.2f);
                case CHASSIS, TIRES -> new Focus(62, 4.2f, 0.9f);
            };
        }
        if (!inItems) {
            return new Focus(35 + time * 4, 6.6f, 1.7f);
        }
        return switch (category) {
            case 0 -> switch (item) {
                case 0 -> new Focus(22, 5.2f, 1.2f);
                case 1 -> new Focus(90, 5.8f, 1.0f);
                case 2 -> new Focus(165, 5.2f, 1.1f);
                case 3 -> new Focus(155, 6.0f, 2.3f);
                default -> new Focus(48, 5.0f, 1.2f);
            };
            case 1 -> new Focus(40 + time * 6, 6.4f, 1.8f);
            default -> switch (item) {
                case 0, 1, 2 -> new Focus(68, 4.0f, 0.8f);
                default -> new Focus(115, 5.4f, 1.5f);
            };
        };
    }

    // ------------------------------------------------------------------ 그리기

    public void draw(UiCanvas c) {
        float s = c.scale();
        float w = c.width();
        float h = c.height();
        // 화면 가장자리 어둡게 (차가 돋보이게)
        c.rectH(0, 0, w * 0.42f, h, 0x05080DCC, 0x05080D00);
        c.rectH(w * 0.68f, 0, w * 0.32f, h, 0x05080D00, 0x05080DB3);
        c.rectV(0, h - 110 * s, w, 110 * s, 0x05080D00, 0x05080DCC);

        Theme.slantTitle(c, 36 * s, 26 * s, kind.title, kind.label + "  ·  " + (kind == Shops.Kind.TUNING
                ? "성능 부품을 단계별로 올립니다" : "고르는 즉시 차에 입혀 봅니다"), kind.ui);
        Theme.pill(c, w - 36 * s, 34 * s, 20 * s, Theme.money(session.profile().money()), 0x0B0F16D9, Theme.TEXT, Align.RIGHT);

        float top = 104 * s;
        float panelH = h - top - 100 * s;
        drawCategories(c, 36 * s, top, 214 * s, panelH);
        drawItems(c, 262 * s, top, 336 * s, panelH);
        if (kind == Shops.Kind.TUNING) {
            drawStats(c, w - 36 * s - 330 * s, top, 330 * s, panelH);
        } else {
            drawStyleSummary(c, w - 36 * s - 330 * s, top, 330 * s, panelH);
        }
        drawKeys(c, 36 * s, h - 52 * s);
    }

    private void drawCategories(UiCanvas c, float x, float y, float w, float h) {
        float s = c.scale();
        Theme.panel(c, x, y, w, h, 14 * s);
        c.text(x + 18 * s, y + 14 * s, 13 * s, "분류", Theme.DIM, Align.LEFT);
        int n = categoryCount();
        float rowH = Math.min(58 * s, (h - 46 * s) / n);
        for (int i = 0; i < n; i++) {
            float ry = y + 38 * s + i * rowH;
            boolean sel = i == category;
            float rx = x + 10 * s;
            float rw = w - 20 * s;
            if (sel) {
                Theme.row(c, rx, ry, rw, rowH - 6 * s, 10 * s, !inItems);
                if (inItems) {
                    c.roundFrame(rx, ry, rw, rowH - 6 * s, 10 * s, 2 * s, kind.ui);
                }
            }
            int fg = sel && !inItems ? 0x0B0F16FF : Theme.TEXT;
            int fg2 = sel && !inItems ? 0x0B0F16B0 : Theme.DIM;
            String label = kind == Shops.Kind.TUNING ? Part.Category.values()[i].label() : STYLE_CATS[i];
            String hint = kind == Shops.Kind.TUNING ? Part.Category.values()[i].hint() : STYLE_HINTS[i];
            c.text(rx + 14 * s, ry + 7 * s, 19 * s, label, fg, Align.LEFT);
            c.text(rx + 14 * s, ry + 31 * s, 11.5f * s, hint, fg2, Align.LEFT);
            if (kind == Shops.Kind.TUNING) {
                // 이 분류의 진행 (단계 합 / 최대)
                int lv = 0, max = 0;
                for (Part p : Part.Category.values()[i].parts()) {
                    lv += car().tuning().level(p);
                    max += p.maxLevel();
                }
                c.text(rx + rw - 12 * s, ry + 9 * s, 14 * s, lv + "/" + max, sel && !inItems ? 0x0B0F16FF : kind.ui, Align.RIGHT);
            }
        }
    }

    private void drawItems(UiCanvas c, float x, float y, float w, float h) {
        float s = c.scale();
        Theme.panel(c, x, y, w, h, 14 * s);
        String title = kind == Shops.Kind.TUNING ? Part.Category.values()[category].label() : STYLE_CATS[category];
        c.text(x + 20 * s, y + 14 * s, 22 * s, title, Theme.TEXT, Align.LEFT);
        c.rect(x + 20 * s, y + 46 * s, w - 40 * s, Math.max(1, s), Theme.LINE);
        if (kind == Shops.Kind.TUNING) {
            drawParts(c, x, y + 58 * s, w, h - 66 * s);
        } else {
            drawStyleRows(c, x, y + 58 * s, w, h - 58 * s);
        }
    }

    private void drawParts(UiCanvas c, float x, float y, float w, float h) {
        float s = c.scale();
        Profile.OwnedCar oc = car();
        CarSpec stock = garage().entry(oc.carId()).spec();
        CarClass stockClass = session.market().stockClass(stock);
        List<TuneRow> rows = tuneRows();
        int now = PerformanceRating.of(Tuning.apply(stock, oc.tuning()));
        // 목록이 길면 고른 줄이 보이게 올린다
        float[] heights = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            heights[i] = (rows.get(i).part() != null ? 112 : 80) * s;
        }
        int first = 0;
        int sel0 = inItems ? Math.min(item, rows.size() - 1) : 0;
        while (first < sel0) {
            float sum = 0;
            for (int i = first; i <= sel0; i++) {
                sum += heights[i];
            }
            if (sum <= h - 8 * s) {
                break;
            }
            first++;
        }
        if (first > 0) {
            c.text(x + w - 24 * s, y - 34 * s, 12 * s, "▲ " + first, Theme.DIM, Align.RIGHT);
        }
        float ry = y;
        for (int i = first; i < rows.size(); i++) {
            if (ry + heights[i] > y + h + 4 * s) {
                c.text(x + w - 24 * s, y + h - 4 * s, 12 * s, "▼ " + (rows.size() - i), Theme.DIM, Align.RIGHT);
                break;
            }
            boolean sel = inItems && i == item;
            if (rows.get(i).adjust() != null) {
                drawAdjust(c, rows.get(i).adjust(), x + 12 * s, ry, w - 24 * s, sel);
                ry += heights[i];
                continue;
            }
            Part p = rows.get(i).part();
            float rowY = ry;
            ry += heights[i];
            float rx = x + 12 * s;
            float rw = w - 24 * s;
            c.roundRect(rx, rowY, rw, 104 * s, 12 * s, sel ? 0xFFFFFF1F : Theme.ROW);
            if (sel) {
                c.roundFrame(rx, rowY, rw, 104 * s, 12 * s, 2 * s, kind.ui);
            }
            // 설명: 칸 폭에 맞춰 글자를 줄임
            String desc = describe(p);
            float dpx = 12 * s;
            while (dpx > 9 * s && c.textWidth(desc, dpx) > rw - 32 * s) {
                dpx -= 0.5f * s;
            }
            c.text(rx + 16 * s, rowY + 74 * s, dpx, desc, Theme.DIM, Align.LEFT);
            int lv = oc.tuning().level(p);
            c.text(rx + 16 * s, rowY + 12 * s, 19 * s, p.label(), Theme.TEXT, Align.LEFT);
            Theme.pips(c, rx + 16 * s, rowY + 44 * s, lv, p.maxLevel(), 11 * s, kind.ui);
            String right;
            int color;
            String delta = "";
            if (!Tuning.available(stock, p)) {
                right = "장착 불가";
                color = Theme.DIM;
            } else if (lv >= p.maxLevel()) {
                right = "MAX";
                color = Theme.GOOD;
            } else {
                int price = Tuning.price(p, lv + 1, stockClass);
                right = Theme.money(price);
                color = session.profile().money() >= price ? Theme.TEXT : Theme.BAD;
                int after = PerformanceRating.of(Tuning.apply(stock, oc.tuning().with(p, lv + 1)));
                delta = after > now ? "성능 +" + (after - now) : "";
            }
            c.text(rx + rw - 16 * s, rowY + 12 * s, 18 * s, right, color, Align.RIGHT);
            if (!delta.isEmpty()) {
                c.text(rx + rw - 16 * s, rowY + 42 * s, 14 * s, delta, Theme.GOOD, Align.RIGHT);
            }
            c.text(rx + 16 * s + p.maxLevel() * 24 * s + 10 * s, rowY + 42 * s, 13 * s, lv == 0 ? "순정" : lv + "단계", Theme.DIM, Align.LEFT);
        }
    }

    /** 직접 맞추는 값 한 줄: 이름, 값, 막대, ◀ ▶ */
    private void drawAdjust(UiCanvas c, Adjust a, float rx, float ry, float rw, boolean sel) {
        float s = c.scale();
        c.roundRect(rx + 18 * s, ry, rw - 18 * s, 72 * s, 12 * s, sel ? 0xFFFFFF1F : 0xFFFFFF0A);
        if (sel) {
            c.roundFrame(rx + 18 * s, ry, rw - 18 * s, 72 * s, 12 * s, 2 * s, kind.ui);
        }
        c.rect(rx + 6 * s, ry + 8 * s, 3 * s, 56 * s, UiCanvas.alpha(kind.ui, 0.7f));
        float v = adjustValue(a);
        boolean camber = a.part == Part.CAMBER;
        String text = camber ? String.format("%.1f°", v) : String.format("+%.0f mm", v);
        c.text(rx + 34 * s, ry + 10 * s, 14 * s, a.label, Theme.DIM, Align.LEFT);
        c.text(rx + rw - 16 * s, ry + 8 * s, 20 * s, text, Theme.TEXT, Align.RIGHT);
        float frac = camber ? v / (a == Adjust.CAMBER_FRONT ? driftengine.game.Setup.CAMBER_FRONT_MIN : driftengine.game.Setup.CAMBER_REAR_MIN)
                : v / driftengine.game.Setup.OFFSET_MAX;
        Theme.bar(c, rx + 34 * s, ry + 46 * s, rw - 110 * s, 6 * s, frac, frac, kind.ui);
        if (sel) {
            c.text(rx + rw - 38 * s, ry + 38 * s, 16 * s, "◀ ▶", Theme.TEXT, Align.CENTER);
        }
    }

    /** 부품 설명 (한 줄) */
    static String describe(Part p) {
        return switch (p) {
            case ENGINE -> "흡기 필터·배기: 토크 +5% / +10% / +15%, 배기음 커짐";
            case ECU -> "맵핑: 토크 +4% / +8%, 회전 한계 +250 / +500rpm";
            case CAMSHAFT -> "고회전 토크 +6~18% (저회전 약간 ↓), 한계 +150rpm";
            case CYLINDER -> "보어업: 배기량↑ 토크 +6% / +12%, 회전 질량 조금 늘어남";
            case IGNITION -> "플러그·코일: 토크 +2% / +4%, 회전이 빨리 오름";
            case SUPERCHARGER -> "저회전부터 바로 부스트 0.35~0.75bar (터보와 하나만)";
            case NITRO -> "Shift 분사: 토크 +20~40%, 4~8초 (드리프트로 충전)";
            case CAMBER -> "1단계 −1.5° · 2단계 −3° · 3단계 앞뒤 직접 맞춤";
            case OFFSET -> "스페이서: 앞뒤 0~40mm 직접, 트랙이 넓어져 안정";
            case TURBO -> "자연흡기는 터보 키트, 터보 차는 단계마다 +0.3bar";
            case GEARBOX -> "변속 시간 절반 + 최종 감속비 5% 짧게";
            case LSD -> "뒷바퀴 두 개를 묶어 드리프트 유지가 쉬움";
            case FUEL_TANK -> "알루미늄 탱크 −12kg / 카본 탱크 −25kg";
            case AERO -> "다운포스 +29 / +67 / +116kg (200km/h), 저항 조금 증가";
            case WEIGHT -> "내장재·패널 경량화: 무게 −3% / −6% / −10%";
            case SUSPENSION -> "단단한 스프링·댐퍼, 차고 −1.5cm / −3cm";
            case BRAKES -> "제동력 +15% / +30%";
            case STEERING -> "최대 조향각 +5° / +12° (깊은 드리프트 각)";
            case TIRES -> "그립 μ +0.05 / +0.12 / +0.22";
        };
    }

    private void drawStyleRows(UiCanvas c, float x, float y, float w, float h) {
        float s = c.scale();
        String[] rows = STYLE_ROWS[category];
        int saveItem = item;
        for (int i = 0; i < rows.length; i++) {
            item = i;
            int[] o = option();
            item = saveItem;
            float ry = y + i * 84 * s;
            boolean sel = inItems && i == item;
            float rx = x + 12 * s;
            float rw = w - 24 * s;
            c.roundRect(rx, ry, rw, 76 * s, 12 * s, sel ? 0xFFFFFF1F : Theme.ROW);
            if (sel) {
                c.roundFrame(rx, ry, rw, 76 * s, 12 * s, 2 * s, kind.ui);
            }
            c.text(rx + 16 * s, ry + 10 * s, 15 * s, rows[i], Theme.DIM, Align.LEFT);
            String value = optionName(i, o[1]);
            boolean changed = isChanged(i);
            c.text(rx + 16 * s, ry + 36 * s, 20 * s, value, changed ? kind.ui : Theme.TEXT, Align.LEFT);
            // 색 견본
            Integer swatch = swatch(i, o[1]);
            if (swatch != null) {
                float cx = rx + rw - 96 * s;
                c.circle(cx, ry + 46 * s, 13 * s, 0xFFFFFFFF);
                c.circle(cx, ry + 46 * s, 11 * s, swatch);
            }
            if (sel) {
                c.text(rx + rw - 30 * s, ry + 34 * s, 20 * s, "◀ ▶", Theme.TEXT, Align.CENTER);
            }
            // 단계 위치 점
            float dotX = rx + 16 * s;
            int count = Math.min(o[0], 21);
            for (int k = 0; k < count; k++) {
                c.circle(dotX + k * 9 * s, ry + 66 * s, 2.4f * s, k == o[1] ? kind.ui : 0xFFFFFF33);
            }
        }
        // 색 줄 (페인팅 차체 색이 선택되면 팔레트 전체)
        if (category == 1 && inItems && item == 0) {
            float py = y + rows.length * 84 * s + 10 * s;
            c.text(x + 24 * s, py, 13 * s, "팔레트", Theme.DIM, Align.LEFT);
            int cols = 7;
            for (int k = 0; k < Livery.PAINTS.size(); k++) {
                float cx = x + 38 * s + (k % cols) * 40 * s;
                float cy = py + 36 * s + (k / cols) * 40 * s;
                if (k == pendingLivery.paint()) {
                    c.circle(cx, cy, 17 * s, kind.ui);
                }
                c.circle(cx, cy, 14 * s, Livery.PAINTS.get(k).rgba());
            }
        }
    }

    private boolean isChanged(int row) {
        Profile.OwnedCar oc = car();
        BodyKit a = oc.tuning().kit();
        Livery l = oc.livery();
        return switch (category) {
            case 0 -> {
                int[] u = {a.front(), a.side(), a.rear(), a.spoiler(), a.widebody()};
                int[] v = {pendingKit.front(), pendingKit.side(), pendingKit.rear(), pendingKit.spoiler(), pendingKit.widebody()};
                yield u[row] != v[row];
            }
            case 1 -> row == 0 ? l.paint() != pendingLivery.paint() : l.finish() != pendingLivery.finish();
            default -> switch (row) {
                case 0 -> !java.util.Objects.equals(l.rimFrom(), pendingLivery.rimFrom());
                case 1 -> l.rimColor() != pendingLivery.rimColor();
                case 2 -> l.caliper() != pendingLivery.caliper();
                default -> l.tint() != pendingLivery.tint();
            };
        };
    }

    private String optionName(int row, int index) {
        return switch (category) {
            case 0 -> KIT_NAMES[row][index];
            case 1 -> index == 0 ? "순정" : row == 0 ? Livery.PAINTS.get(index - 1).name() : Livery.FINISHES.get(index - 1);
            default -> switch (row) {
                case 0 -> index == 0 ? "순정" : garage().entry(rimDesigns.get(index)).spec().name() + " 휠";
                case 1 -> index == 0 ? "순정" : Livery.RIM_COLORS.get(index - 1).name();
                case 2 -> index == 0 ? "순정" : Livery.CALIPERS.get(index - 1).name();
                default -> Livery.TINTS.get(index);
            };
        };
    }

    private Integer swatch(int row, int index) {
        if (index == 0 && !(category == 2 && row == 3)) {
            return null;
        }
        if (category == 1 && row == 0) {
            return Livery.PAINTS.get(index - 1).rgba();
        }
        if (category == 2 && row == 1) {
            return Livery.RIM_COLORS.get(index - 1).rgba();
        }
        if (category == 2 && row == 2) {
            return Livery.CALIPERS.get(index - 1).rgba();
        }
        if (category == 2 && row == 3) {
            int a = Math.round(40 + 200 * Livery.TINT_VALUE[index]);
            return 0x10141C00 | a;
        }
        return null;
    }

    /** 튜닝샵 오른쪽: 차 이름, 클래스, 성능 막대 (지금 / 고른 부품을 올리면) */
    private void drawStats(UiCanvas c, float x, float y, float w, float h) {
        float s = c.scale();
        Theme.panel(c, x, y, w, h, 14 * s);
        Profile.OwnedCar oc = car();
        CarSpec stock = garage().entry(oc.carId()).spec();
        CarSpec now = Tuning.apply(stock, oc.tuning());
        CarSpec after = now;
        List<TuneRow> rowsNow = tuneRows();
        Part p = inItems && item < rowsNow.size() ? rowsNow.get(item).part() : null;
        if (p != null) {
            int lv = oc.tuning().level(p);
            if (lv < p.maxLevel() && Tuning.available(stock, p)) {
                TuningState next = oc.tuning().with(p, lv + 1);
                after = Tuning.apply(stock, next);
            }
        }
        PerformanceRating.Breakdown bn = PerformanceRating.breakdown(now);
        PerformanceRating.Breakdown ba = PerformanceRating.breakdown(after);
        CarClass cn = session.market().classes().classOf(bn.total());
        CarClass ca = session.market().classes().classOf(ba.total());
        c.text(x + 20 * s, y + 16 * s, 13 * s, stock.brand().toUpperCase(), Theme.DIM, Align.LEFT);
        c.text(x + 20 * s, y + 34 * s, 24 * s, stock.name(), Theme.TEXT, Align.LEFT);
        Theme.classBadge(c, x + 20 * s, y + 74 * s, 40 * s, cn.label());
        c.text(x + 80 * s, y + 70 * s, 38 * s, Integer.toString(bn.total()), Theme.TEXT, Align.LEFT);
        if (ba.total() != bn.total()) {
            float tx = x + 80 * s + c.textWidth(Integer.toString(bn.total()), 38 * s) + 14 * s;
            c.text(tx, y + 82 * s, 20 * s, "→", Theme.DIM, Align.LEFT);
            c.text(tx + 26 * s, y + 74 * s, 32 * s, Integer.toString(ba.total()), Theme.GOOD, Align.LEFT);
            if (ca != cn) {
                Theme.classBadge(c, tx + 26 * s + c.textWidth(Integer.toString(ba.total()), 32 * s) + 10 * s, y + 78 * s, 30 * s, ca.label());
            }
        }
        c.rect(x + 20 * s, y + 132 * s, w - 40 * s, Math.max(1, s), Theme.LINE);

        float sy = y + 148 * s;
        sy = stat(c, x, sy, w, "출력", String.format("%.0f PS", now.peakPowerKw() * 1.36f), String.format("%.0f PS", after.peakPowerKw() * 1.36f),
                now.peakPowerKw() * 1.36f / 800, after.peakPowerKw() * 1.36f / 800);
        sy = stat(c, x, sy, w, "무게", String.format("%.0f kg", now.mass()), String.format("%.0f kg", after.mass()),
                (2400 - now.mass()) / 1600, (2400 - after.mass()) / 1600);
        sy = stat(c, x, sy, w, "가속", Integer.toString(bn.acceleration()), Integer.toString(ba.acceleration()),
                bn.acceleration() / 1000f, ba.acceleration() / 1000f);
        sy = stat(c, x, sy, w, "코너링", Integer.toString(bn.cornering()), Integer.toString(ba.cornering()),
                bn.cornering() / 1000f, ba.cornering() / 1000f);
        float dfN = 0.5f * CarSpec.AIR_DENSITY * now.downforceArea() * 55.6f * 55.6f / CarSpec.GRAVITY;
        float dfA = 0.5f * CarSpec.AIR_DENSITY * after.downforceArea() * 55.6f * 55.6f / CarSpec.GRAVITY;
        sy = stat(c, x, sy, w, "다운포스 (200km/h)", String.format("%.0f kg", dfN), String.format("%.0f kg", dfA), dfN / 500, dfA / 500);
        stat(c, x, sy, w, "최고 속도", String.format("%.0f km/h", bn.topSpeedKmh()), String.format("%.0f km/h", ba.topSpeedKmh()),
                bn.topSpeedKmh() / 400, ba.topSpeedKmh() / 400);
    }

    private float stat(UiCanvas c, float x, float y, float w, String name, String now, String after, float a, float b) {
        float s = c.scale();
        c.text(x + 20 * s, y, 14 * s, name, Theme.DIM, Align.LEFT);
        boolean changed = !now.equals(after);
        c.text(x + w - 20 * s, y, 15 * s, changed ? now + "  →  " + after : now, changed ? Theme.GOOD : Theme.TEXT, Align.RIGHT);
        Theme.bar(c, x + 20 * s, y + 22 * s, w - 40 * s, 7 * s, a, b, kind.ui);
        return y + 46 * s;
    }

    /** 스타일링샵 오른쪽: 바꾼 것 목록 + 합계 */
    private void drawStyleSummary(UiCanvas c, float x, float y, float w, float h) {
        float s = c.scale();
        Theme.panel(c, x, y, w, h, 14 * s);
        CarSpec spec = garage().currentSpec();
        c.text(x + 20 * s, y + 16 * s, 13 * s, spec.brand().toUpperCase(), Theme.DIM, Align.LEFT);
        c.text(x + 20 * s, y + 34 * s, 24 * s, spec.name(), Theme.TEXT, Align.LEFT);
        c.rect(x + 20 * s, y + 74 * s, w - 40 * s, Math.max(1, s), Theme.LINE);
        c.text(x + 20 * s, y + 88 * s, 15 * s, "장바구니", Theme.TEXT, Align.LEFT);
        float ly = y + 118 * s;
        int saveCat = category;
        int saveItem = item;
        int lines = 0;
        for (int cat = 0; cat < STYLE_ROWS.length; cat++) {
            category = cat;
            for (int r = 0; r < STYLE_ROWS[cat].length; r++) {
                if (isChanged(r)) {
                    item = r;
                    String v = optionName(r, option()[1]);
                    c.text(x + 24 * s, ly, 14 * s, STYLE_ROWS[cat][r], Theme.DIM, Align.LEFT);
                    c.text(x + w - 24 * s, ly, 14 * s, v, kind.ui, Align.RIGHT);
                    ly += 26 * s;
                    lines++;
                }
            }
        }
        category = saveCat;
        item = saveItem;
        if (lines == 0) {
            c.text(x + 24 * s, ly, 14 * s, "바꾼 것이 없습니다", Theme.DIM, Align.LEFT);
            c.text(x + 24 * s, ly + 24 * s, 13 * s, "항목을 고르고 ←→ 로 바꿔 보세요", Theme.DIM, Align.LEFT);
        }
        int cost = pendingCost();
        float by = y + h - 92 * s;
        c.rect(x + 20 * s, by, w - 40 * s, Math.max(1, s), Theme.LINE);
        c.text(x + 20 * s, by + 14 * s, 15 * s, "합계", Theme.DIM, Align.LEFT);
        c.text(x + w - 20 * s, by + 10 * s, 24 * s, Theme.money(cost), session.profile().money() >= cost ? Theme.TEXT : Theme.BAD, Align.RIGHT);
        float bw = w - 40 * s;
        boolean any = pendingChanges();
        c.roundRectV(x + 20 * s, by + 46 * s, bw, 36 * s, 10 * s, any ? kind.ui : 0xFFFFFF14,
                any ? UiCanvas.lerpColor(kind.ui, 0x000000FF, 0.2f) : 0xFFFFFF14);
        c.text(x + 20 * s + bw / 2, by + 54 * s, 17 * s, any ? "Enter  구입하기" : "바꾼 것 없음", any ? 0x0B0F16FF : Theme.DIM, Align.CENTER);
    }

    private void drawKeys(UiCanvas c, float x, float y) {
        float kx = x;
        kx = Theme.key(c, kx, y, "↑↓", "고르기");
        if (!inItems) {
            kx = Theme.key(c, kx, y, "Enter", "열기");
            Theme.key(c, kx, y, "Backspace", "샵 나가기");
        } else if (kind == Shops.Kind.TUNING) {
            boolean adjusting = item < tuneRows().size() && tuneRows().get(item).adjust() != null;
            if (adjusting) {
                kx = Theme.key(c, kx, y, "←→", "값 맞추기 (무료)");
                Theme.key(c, kx, y, "Backspace", "분류로");
            } else {
                kx = Theme.key(c, kx, y, "Enter", "올리기");
                kx = Theme.key(c, kx, y, "← ←", "내리기 (반값 돌려받음)");
                Theme.key(c, kx, y, "Backspace", "분류로");
            }
        } else {
            kx = Theme.key(c, kx, y, "←→", "바꿔 보기");
            kx = Theme.key(c, kx, y, "Enter", "구입");
            Theme.key(c, kx, y, "Backspace", "분류로");
        }
    }
}
