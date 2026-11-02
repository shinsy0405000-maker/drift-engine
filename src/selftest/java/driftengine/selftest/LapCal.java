package driftengine.selftest;

import driftengine.game.ClassTable;
import driftengine.game.PerformanceRating;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.world.map.Course;
import driftengine.world.map.Zone;
import driftengine.world.map.ZoneMap;
import driftengine.world.map.Zones;

import java.nio.file.Path;

/** 개발용: 모든 차로 서킷 한 바퀴 (컴퓨터 운전자) → 랩 목표 시간 정하는 자료 */
public final class LapCal {
    public static void main(String[] a) {
        boolean pass = Boolean.getBoolean("lapcal.pass");
        ZoneMap m = Zones.build(pass ? Zone.PASS : Zone.CIRCUIT);
        Course c = m.course(pass ? Zones.PASS_COURSE : Zones.CIRCUIT_COURSE);
        for (CarCatalog.Entry e : CarCatalog.load(Path.of("cars"))) {
            if (a.length > 0 && !e.spec().id().contains(a[0])) {
                continue;
            }
            long t0 = System.nanoTime();
            LapBot.Result r = LapBot.run(e.spec(), c, pass ? 20 : 25);
            int rating = PerformanceRating.of(e.spec());
            System.out.printf("%-20s %4d %-3s lap %7.2f s  off %.1f m  top %.0f km/h  (%.0f ms)%n", e.spec().id(), rating,
                    ClassTable.DEFAULT.classOf(rating).label(), r.lap(), r.maxOff(), r.topKmh(), (System.nanoTime() - t0) / 1e6);
        }
    }
}
