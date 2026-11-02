package driftengine.vehicle.visual;

import driftengine.audio.SoundProfile;
import driftengine.core.Log;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.CarSpecLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/** cars 폴더의 차 목록: 성능(CarSpec) + 겉모양(CarStyle) + 소리(SoundProfile). 파일 이름 순서 */
public final class CarCatalog {

    /** 차 하나: 성능 + 겉모양 + 소리 성격 */
    public record Entry(CarSpec spec, CarStyle style, SoundProfile sound) {

        public Entry(CarSpec spec, CarStyle style) {
            this(spec, style, SoundProfile.defaults(spec.engine()));
        }
    }

    private CarCatalog() {
    }

    public static List<Entry> load(Path dir) {
        List<Entry> out = new ArrayList<>();
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
                    CarSpec spec = CarSpecLoader.parse(id, p);
                    out.add(new Entry(spec, CarStyle.fromProperties(spec, p), SoundProfile.fromProperties(spec.engine(), p)));
                } catch (IOException | RuntimeException e) {
                    Log.warn("차량 파일 건너뜀: " + file.getFileName() + " → " + e.getMessage());
                }
            }
        }
        if (out.isEmpty()) {
            Log.info("차량 파일이 없어 내장 기본 차를 씁니다 (" + dir.toAbsolutePath() + ")");
            CarSpec spec = CarSpecLoader.builtIn();
            out.add(new Entry(spec, CarStyle.defaults(spec)));
        }
        return out;
    }
}
