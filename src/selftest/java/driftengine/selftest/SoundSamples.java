package driftengine.selftest;

import driftengine.audio.CarSynth;
import driftengine.audio.WavWriter;
import driftengine.vehicle.visual.CarCatalog;

import java.nio.file.Path;
import java.util.List;

/**
 * 차마다 소리 샘플 WAV 를 만든다 (소리 장치 불필요): ./gradlew soundSamples → build/sound-samples
 * <ul>
 *   <li>NN-이름-rev.wav : 제자리 공회전 → 풀 스로틀 → 레드존 → 떼기 (블로우오프·팝)</li>
 *   <li>NN-이름-drive.wav : 출발 가속(변속) → 떼기 → 핸드브레이크 드리프트 (타이어 비명)</li>
 * </ul>
 */
public final class SoundSamples {

    private SoundSamples() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/sound-samples");
        List<CarCatalog.Entry> cars = CarCatalog.load(Path.of("cars"));
        for (CarCatalog.Entry e : cars) {
            String id = e.spec().id();
            float[] rev = SoundLab.revSweep(SoundLab.synth(e.sound()), e.spec());
            WavWriter.write(dir.resolve(id + "-rev.wav"), rev, (int) CarSynth.SAMPLE_RATE);
            float[] squeal = new float[1];
            CarSynth s = SoundLab.synth(e.sound());
            float[] drive = SoundLab.drive(s, e.spec(), squeal);
            WavWriter.write(dir.resolve(id + "-drive.wav"), drive, (int) CarSynth.SAMPLE_RATE);
            System.out.printf("%-22s %-10s rev: 크기 %.3f 최대 %.2f | 주행: 크기 %.3f 최대 %.2f, 팝 %d, 블로우오프 %d, 비명 최대 %.2f%n",
                    id, e.sound().layout(), SoundLab.rms(rev), SoundLab.peak(rev), SoundLab.rms(drive), SoundLab.peak(drive),
                    s.pops(), s.blowOffs(), squeal[0]);
        }
        System.out.println("저장: " + dir.toAbsolutePath());
    }
}
