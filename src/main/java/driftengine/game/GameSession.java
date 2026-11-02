package driftengine.game;

import driftengine.core.Log;
import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.CarCatalog;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 게임 한 판에 필요한 것 묶음: 차 목록, 가격표, 클럽, 플레이어 진행(저장 파일), 차고.
 * 저장 파일이 없으면 새 게임(돈 20,000 CR + 86R). game.properties 가 있으면 클래스 경계를 읽는다.
 */
public final class GameSession {

    public static final Path SAVE_FILE = Path.of("save", "profile.properties");
    public static final Path CONFIG_FILE = Path.of("game.properties");

    private final List<CarCatalog.Entry> catalog;
    private final Market market;
    private final List<Club> clubs;
    private final Profile profile;
    private final Garage garage;
    private final Path saveFile;

    public GameSession(List<CarCatalog.Entry> catalog, Path saveFile, Path configFile) {
        this.catalog = catalog;
        this.saveFile = saveFile;
        this.market = new Market(loadClasses(configFile));
        this.clubs = Club.defaults();
        this.profile = loadProfile(saveFile, catalog);
        this.garage = new Garage(profile, catalog, market);
    }

    private static ClassTable loadClasses(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return ClassTable.DEFAULT;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            return ClassTable.from(p);
        } catch (IOException | RuntimeException e) {
            Log.warn("game.properties 를 읽지 못해 기본 클래스 경계를 씁니다: " + e.getMessage());
            return ClassTable.DEFAULT;
        }
    }

    private static Profile loadProfile(Path file, List<CarCatalog.Entry> catalog) {
        if (file != null && Files.isRegularFile(file)) {
            try {
                Profile p = Profile.load(file);
                Log.info("저장 불러옴: 돈 " + p.money() + " CR, 차 " + p.cars().size() + "대, 별 " + p.totalStars() + "개");
                return p;
            } catch (IOException | RuntimeException e) {
                Log.warn("저장 파일을 읽지 못해 새 게임으로 시작합니다: " + e.getMessage());
            }
        }
        BodyKit kit = BodyKit.STOCK;
        for (CarCatalog.Entry e : catalog) {
            if (e.spec().id().equals(Profile.STARTER_CAR)) {
                kit = e.style().kit();
            }
        }
        Log.info("새 게임: 돈 " + Profile.START_MONEY + " CR, 시작 차 " + Profile.STARTER_CAR);
        return Profile.newGame(kit);
    }

    public void save() {
        if (saveFile == null) {
            return;
        }
        try {
            profile.save(saveFile);
        } catch (IOException e) {
            Log.warn("저장 실패: " + e.getMessage());
        }
    }

    public List<CarCatalog.Entry> catalog() {
        return catalog;
    }

    public Market market() {
        return market;
    }

    public List<Club> clubs() {
        return clubs;
    }

    public Profile profile() {
        return profile;
    }

    public Garage garage() {
        return garage;
    }
}
