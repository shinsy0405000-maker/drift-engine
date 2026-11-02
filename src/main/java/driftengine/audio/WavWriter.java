package driftengine.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** 스테레오 float 샘플(±1)을 16비트 WAV 파일로 (소리 샘플 확인·테스트용) */
public final class WavWriter {

    private WavWriter() {
    }

    public static void write(Path file, float[] interleaved, int sampleRate) throws IOException {
        int frames = interleaved.length / 2;
        int dataBytes = frames * 4;
        ByteBuffer b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(sampleRate).putInt(sampleRate * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes()).putInt(dataBytes);
        for (float v : interleaved) {
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, v)) * 32767));
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, b.array());
    }
}
