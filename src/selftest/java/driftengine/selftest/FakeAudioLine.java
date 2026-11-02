package driftengine.selftest;

import driftengine.audio.CarAudio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;

/**
 * 소리 장치 대신 쓰는 가짜 줄: 받은 바이트를 모으고, 실제 장치처럼 재생 시간만큼 기다린다(속도 맞춤 흉내).
 * 오디오 스레드·차 바꾸기·닫기를 장치 없이 시험한다.
 */
final class FakeAudioLine implements SourceDataLine {

    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private final boolean realtime;
    private volatile boolean open = true;
    private volatile boolean running;

    FakeAudioLine(boolean realtime) {
        this.realtime = realtime;
    }

    synchronized byte[] bytes() {
        return data.toByteArray();
    }

    /** 받은 소리 (스테레오 float) */
    float[] samples() {
        byte[] b = bytes();
        float[] out = new float[b.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8)) / 32768f;
        }
        return out;
    }

    @Override
    public int write(byte[] b, int off, int len) {
        if (!open) {
            throw new IllegalStateException("닫힌 줄에 씀");
        }
        synchronized (this) {
            data.write(b, off, len);
        }
        if (realtime) {
            try {
                Thread.sleep(Math.max(1, (long) (len / 4.0 / 44100 * 1000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return len;
    }

    @Override
    public void open(AudioFormat format, int bufferSize) {
        open = true;
    }

    @Override
    public void open(AudioFormat format) {
        open = true;
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void drain() {
    }

    @Override
    public void flush() {
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isActive() {
        return running;
    }

    @Override
    public AudioFormat getFormat() {
        return CarAudio.FORMAT;
    }

    @Override
    public int getBufferSize() {
        return 8192;
    }

    @Override
    public int available() {
        return 8192;
    }

    @Override
    public int getFramePosition() {
        return 0;
    }

    @Override
    public long getLongFramePosition() {
        return 0;
    }

    @Override
    public long getMicrosecondPosition() {
        return 0;
    }

    @Override
    public float getLevel() {
        return 0;
    }

    @Override
    public javax.sound.sampled.Line.Info getLineInfo() {
        return new DataLine.Info(SourceDataLine.class, CarAudio.FORMAT);
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public Control[] getControls() {
        return new Control[0];
    }

    @Override
    public boolean isControlSupported(Control.Type control) {
        return false;
    }

    @Override
    public Control getControl(Control.Type control) {
        throw new IllegalArgumentException("없음");
    }

    @Override
    public void addLineListener(LineListener listener) {
    }

    @Override
    public void removeLineListener(LineListener listener) {
    }
}
