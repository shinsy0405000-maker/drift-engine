package driftengine.audio;

import driftengine.core.Log;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * 소리 출력: 자바 기본 오디오(javax.sound, 추가 라이브러리 없음)로 44.1 kHz 스테레오 16비트를 낸다.
 * <p>
 * 오디오 스레드가 256 샘플씩 합성해 줄(line)에 쓴다. 줄 버퍼가 차면 write 가 기다리므로 속도가 저절로 맞는다.
 * 게임 스레드는 {@link #push} 로 차 상태를, {@link #setCar} 로 새 소리 성격을 넘긴다 (volatile 로 건넴).
 * 소리 장치가 없거나 열리지 않으면 경고만 남기고 조용히 넘어간다 (게임은 계속).
 */
public final class CarAudio implements AutoCloseable {

    private static final int BLOCK = 256;
    private static final int LINE_BUFFER_BYTES = 8192; // 약 46 ms
    public static final AudioFormat FORMAT = new AudioFormat(CarSynth.SAMPLE_RATE, 16, 2, true, false);

    private volatile SoundFrame frame = SoundFrame.SILENT;
    private volatile CarSynth pending;
    private volatile boolean running;
    private Thread thread;
    private SourceDataLine line;

    private CarAudio() {
    }

    /** 시스템 속성 drift.audio=off 이면 소리를 끈다. drift.volume = 0 ~ 1 (기본 0.8) */
    public static CarAudio start() {
        CarAudio a = new CarAudio();
        if ("off".equalsIgnoreCase(System.getProperty("drift.audio", "on"))) {
            Log.info("소리 꺼짐 (drift.audio=off)");
            return a;
        }
        try {
            SourceDataLine l = AudioSystem.getSourceDataLine(FORMAT);
            l.open(FORMAT, LINE_BUFFER_BYTES);
            l.start();
            a.begin(l);
            Log.info(String.format("소리 시작: %.0f Hz 스테레오, 줄 버퍼 %d 바이트 (%.0f ms)",
                    CarSynth.SAMPLE_RATE, l.getBufferSize(), l.getBufferSize() / 4f / CarSynth.SAMPLE_RATE * 1000));
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            Log.warn("소리 장치를 열지 못함 → 소리 없이 진행: " + e.getMessage());
        }
        return a;
    }

    /** 이미 열린 줄로 시작 (테스트에서 가짜 줄을 넣을 때도 씀) */
    public static CarAudio startWith(SourceDataLine openLine) {
        CarAudio a = new CarAudio();
        a.begin(openLine);
        return a;
    }

    private void begin(SourceDataLine l) {
        line = l;
        running = true;
        thread = new Thread(this::run, "audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public static float defaultVolume() {
        try {
            return Math.max(0, Math.min(1, Float.parseFloat(System.getProperty("drift.volume", "0.8"))));
        } catch (NumberFormatException e) {
            return 0.8f;
        }
    }

    public boolean active() {
        return running;
    }

    /** 다음 블록부터 이 소리로 (차를 바꿨을 때) */
    public void setCar(SoundProfile profile) {
        pending = new CarSynth(profile, CarSynth.SAMPLE_RATE);
    }

    public void push(SoundFrame f) {
        frame = f;
    }

    private void run() {
        float[] buf = new float[BLOCK * 2];
        float[] old = new float[BLOCK * 2];
        byte[] bytes = new byte[BLOCK * 4];
        CarSynth synth = null;
        try {
            while (running) {
                SoundFrame f = frame;
                CarSynth next = pending;
                if (next != null) {
                    pending = null;
                    if (synth != null) {
                        // 바꾸는 블록 하나는 겹쳐서 부드럽게 (딱 소리 방지)
                        synth.render(f, old, BLOCK);
                        next.render(f, buf, BLOCK);
                        for (int i = 0; i < BLOCK; i++) {
                            float t = i / (float) BLOCK;
                            buf[2 * i] = buf[2 * i] * t + old[2 * i] * (1 - t);
                            buf[2 * i + 1] = buf[2 * i + 1] * t + old[2 * i + 1] * (1 - t);
                        }
                        synth = next;
                        write(buf, bytes);
                        continue;
                    }
                    synth = next;
                }
                if (synth == null) {
                    java.util.Arrays.fill(buf, 0);
                } else {
                    synth.render(f, buf, BLOCK);
                }
                write(buf, bytes);
            }
        } catch (RuntimeException e) {
            Log.error("오디오 스레드 오류 → 소리 멈춤", e);
            running = false;
        }
    }

    private void write(float[] buf, byte[] bytes) {
        for (int i = 0; i < BLOCK * 2; i++) {
            int v = Math.round(Math.max(-1, Math.min(1, buf[i])) * 32767);
            bytes[2 * i] = (byte) v;
            bytes[2 * i + 1] = (byte) (v >> 8);
        }
        line.write(bytes, 0, bytes.length);
    }

    @Override
    public void close() {
        if (thread == null) {
            return;
        }
        running = false;
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        line.stop();
        line.flush();
        line.close();
        thread = null;
        Log.info("소리 닫음");
    }
}
