package driftengine.game;

import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.Livery;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * 플레이어 진행 상태: 돈, 가진 차(튜닝 포함), 지금 차, 클럽 이벤트 기록, 열린 차. 파일(save/profile.properties)로 저장.
 */
public final class Profile {

    public static final long START_MONEY = 20_000;
    public static final String STARTER_CAR = "03-tora-86r";

    /** 가진 차 한 대. spent = 이 차에 쓴 돈(차값 + 부품값, 팔 때 절반 돌려받음), livery = 스타일링 샵 꾸미기 */
    public record OwnedCar(String carId, TuningState tuning, long spent, Livery livery) {
        public OwnedCar(String carId, TuningState tuning, long spent) {
            this(carId, tuning, spent, Livery.STOCK);
        }

        public OwnedCar withTuning(TuningState t, long extra) {
            return new OwnedCar(carId, t, spent + extra, livery);
        }

        public OwnedCar withLivery(Livery l, long extra) {
            return new OwnedCar(carId, tuning, spent + extra, l);
        }
    }

    long money;
    final List<OwnedCar> cars = new ArrayList<>();
    int selected;
    final Map<String, Integer> stars = new HashMap<>();
    final Map<String, Float> best = new HashMap<>();
    final Set<String> unlocked = new LinkedHashSet<>();

    public static Profile newGame(BodyKit starterKit) {
        Profile p = new Profile();
        p.money = START_MONEY;
        p.cars.add(new OwnedCar(STARTER_CAR, TuningState.stock(starterKit), 0));
        return p;
    }

    public long money() {
        return money;
    }

    public List<OwnedCar> cars() {
        return List.copyOf(cars);
    }

    public int selectedIndex() {
        return selected;
    }

    public OwnedCar selectedCar() {
        return cars.get(selected);
    }

    public int stars(String eventId) {
        return stars.getOrDefault(eventId, 0);
    }

    public Float best(String eventId) {
        return best.get(eventId);
    }

    public Set<String> unlocked() {
        return Set.copyOf(unlocked);
    }

    /** 테스트·개발용: 돈을 정한다 */
    public void profileMoneyForTest(long amount) {
        money = amount;
    }

    public int totalStars() {
        return stars.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ------------------------------------------------------------------ 저장

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("money", Long.toString(money));
        p.setProperty("selected", Integer.toString(selected));
        for (int i = 0; i < cars.size(); i++) {
            OwnedCar c = cars.get(i);
            p.setProperty("car." + i + ".id", c.carId());
            p.setProperty("car." + i + ".parts", c.tuning().levelsText());
            p.setProperty("car." + i + ".kit", c.tuning().kit().text());
            p.setProperty("car." + i + ".spent", Long.toString(c.spent()));
            p.setProperty("car." + i + ".livery", c.livery().text());
            p.setProperty("car." + i + ".setup", c.tuning().setup().text());
        }
        for (Map.Entry<String, Integer> e : new TreeMap<>(stars).entrySet()) {
            p.setProperty("stars." + e.getKey(), e.getValue().toString());
        }
        for (Map.Entry<String, Float> e : new TreeMap<>(best).entrySet()) {
            p.setProperty("best." + e.getKey(), e.getValue().toString());
        }
        p.setProperty("unlocked", String.join(",", unlocked));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        java.io.StringWriter text = new java.io.StringWriter();
        p.store(text, "Drift Engine 저장 파일 (직접 고치면 돈·차를 바꿀 수 있음)");
        // 사람이 읽기 좋게: 주석 줄은 위에, 나머지는 키 순서로
        List<String> head = new ArrayList<>();
        List<String> body = new ArrayList<>();
        for (String line : text.toString().split("\\R")) {
            (line.startsWith("#") ? head : body).add(line);
        }
        body.sort(null);
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (String line : head) {
                w.write(line + System.lineSeparator());
            }
            for (String line : body) {
                w.write(line + System.lineSeparator());
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static Profile load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        Profile profile = new Profile();
        profile.money = Long.parseLong(p.getProperty("money", "0").trim());
        for (int i = 0; p.getProperty("car." + i + ".id") != null; i++) {
            String id = p.getProperty("car." + i + ".id").trim();
            int[] levels = TuningState.parseLevels(p.getProperty("car." + i + ".parts", "0"));
            BodyKit kit = BodyKit.parse(p.getProperty("car." + i + ".kit", "0,0,0,0,0"));
            long spent = Long.parseLong(p.getProperty("car." + i + ".spent", "0").trim());
            Livery livery = Livery.parse(p.getProperty("car." + i + ".livery"));
            Setup setup = Setup.parse(p.getProperty("car." + i + ".setup"));
            profile.cars.add(new OwnedCar(id, new TuningState(levels, kit, setup), spent, livery));
        }
        if (profile.cars.isEmpty()) {
            throw new IOException("저장 파일에 차가 없습니다: " + file);
        }
        profile.selected = Math.max(0, Math.min(profile.cars.size() - 1, Integer.parseInt(p.getProperty("selected", "0").trim())));
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("stars.")) {
                profile.stars.put(key.substring(6), Integer.parseInt(p.getProperty(key).trim()));
            } else if (key.startsWith("best.")) {
                profile.best.put(key.substring(5), Float.parseFloat(p.getProperty(key).trim()));
            }
        }
        String unlocked = p.getProperty("unlocked", "").trim();
        if (!unlocked.isEmpty()) {
            for (String id : unlocked.split(",")) {
                profile.unlocked.add(id.trim());
            }
        }
        return profile;
    }
}
