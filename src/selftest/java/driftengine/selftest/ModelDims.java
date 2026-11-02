package driftengine.selftest;

import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarStyle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 개발용: cars/*.properties → tools/carmodels/dims.json (Blender 모델링 도구가 읽는 치수).
 * 새 차를 넣으면 이것을 돌린 뒤 build_cars.py 로 모델을 만든다.
 * <pre>java ... driftengine.selftest.ModelDims [cars 폴더] [출력 파일]</pre>
 */
public final class ModelDims {

    private ModelDims() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "cars");
        Path out = Path.of(args.length > 1 ? args[1] : "tools/carmodels/dims.json");
        StringBuilder sb = new StringBuilder("{\n");
        var entries = CarCatalog.load(dir);
        for (int n = 0; n < entries.size(); n++) {
            CarSpec s = entries.get(n).spec();
            CarStyle st = entries.get(n).style();
            sb.append(String.format(Locale.ROOT, "\"%s\": {\"name\":\"%s\",\"category\":\"%s\",\"engine\":\"%s\",\"wheelRadius\":%.4f,"
                            + "\"restHeight\":%.4f,\"half\":[%.4f,%.4f,%.4f],\"mounts\":[",
                    s.id(), s.name(), s.category(), s.engine().type(), s.wheelRadius(), s.restHeight(),
                    s.halfExtents().x(), s.halfExtents().y(), s.halfExtents().z()));
            for (int i = 0; i < 4; i++) {
                var m = s.mount(i);
                sb.append(String.format(Locale.ROOT, "%s[%.4f,%.4f,%.4f]", i == 0 ? "" : ",", m.x(), m.y(), m.z()));
            }
            sb.append(String.format(Locale.ROOT, "],\"style\":{\"length\":%.3f,\"width\":%.3f,\"noseH\":%.3f,\"hoodH\":%.3f,"
                            + "\"roofH\":%.3f,\"deckH\":%.3f,\"tailH\":%.3f,\"sillH\":%.3f,\"beltH\":%.3f,\"cowlU\":%.3f,"
                            + "\"roofStartU\":%.3f,\"roofEndU\":%.3f,\"deckU\":%.3f,\"cabinWidth\":%.3f,\"flare\":%.3f,"
                            + "\"headlight\":%d,\"taillight\":%d,\"bPillar\":%s,\"rim\":%d,\"tireWidthF\":%.3f,\"tireWidthR\":%.3f}}%s\n",
                    st.length(), st.width(), st.noseH(), st.hoodH(), st.roofH(), st.deckH(), st.tailH(), st.sillH(), st.beltH(),
                    st.cowlU(), st.roofStartU(), st.roofEndU(), st.deckU(), st.cabinWidth(), st.flare(), st.headlight(),
                    st.taillight(), st.bPillar(), st.rimStyle(), st.tireWidthF(), st.tireWidthR(), n == entries.size() - 1 ? "" : ","));
        }
        sb.append("}\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("치수 " + entries.size() + "대 → " + out);
    }
}
