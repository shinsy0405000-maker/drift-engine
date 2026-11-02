package driftengine.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 의존성 없는 최소 로거. 콘솔 + (openFile 을 부르면) UTF-8 파일.
 * 윈도우 콘솔·IDE 설정에 따라 콘솔 한글이 깨질 수 있어서, 파일은 항상 UTF-8 로 남긴다.
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintWriter file;

    private Log() {
    }

    /** 이번 실행의 로그 파일을 연다 (덮어씀). 실패하면 콘솔만 쓴다 */
    public static synchronized void openFile(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            file = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), true);
            info("로그 파일: " + path.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            warn("로그 파일을 열지 못함 (콘솔만 씀): " + path + " → " + e.getMessage());
        }
    }

    public static synchronized void closeFile() {
        if (file != null) {
            file.close();
            file = null;
        }
    }

    public static void info(String message) {
        write(false, prefix("INFO ") + message);
    }

    public static void warn(String message) {
        write(false, prefix("WARN ") + message);
    }

    public static void error(String message) {
        write(true, prefix("ERROR") + message);
    }

    public static void error(String message, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        write(true, prefix("ERROR") + message + System.lineSeparator() + sw.toString().stripTrailing());
    }

    private static synchronized void write(boolean err, String line) {
        (err ? System.err : System.out).println(line);
        if (file != null) {
            file.println(line);
        }
    }

    private static String prefix(String level) {
        return "[" + LocalTime.now().format(TIME) + "] [" + level + "] ";
    }
}
