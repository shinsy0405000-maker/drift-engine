package driftengine.core;

import driftengine.input.InputAction;
import driftengine.input.InputMap;
import driftengine.upscale.DlssMode;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 사용자가 직접 고칠 수 있는 설정 파일 (settings.properties, UTF-8).
 * <p>
 * 원칙
 * <ul>
 *   <li>파일이 없으면 기본값으로 새로 만든다 (주석 포함 → 어떤 값을 바꿀 수 있는지 바로 보인다)</li>
 *   <li>값이 잘못돼도 게임은 켜진다: 경고를 남기고 그 항목만 기본값을 쓴다</li>
 *   <li>실행 인자(--width 등)가 설정 파일보다 우선한다 (EngineConfig.fromArgs)</li>
 * </ul>
 */
public final class EngineSettings {

    public static final Path DEFAULT_PATH = Path.of("settings.properties");

    static final String KEY_WIDTH = "window.width";
    static final String KEY_HEIGHT = "window.height";
    static final String KEY_VALIDATION = "render.validation";
    static final String KEY_FOV = "camera.fovDegrees";
    static final String KEY_MOVE_SPEED = "camera.moveSpeed";
    static final String KEY_RENDER_SCALE = "render.scale";
    static final String KEY_DLSS = "render.dlss";
    static final String KEY_DLSS_MODE = "render.dlssMode";
    static final String KEY_VSYNC = "render.vsync";
    static final String INPUT_PREFIX = "input.";
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            KEY_WIDTH, KEY_HEIGHT, KEY_VALIDATION, KEY_FOV, KEY_MOVE_SPEED, KEY_RENDER_SCALE, KEY_DLSS, KEY_DLSS_MODE, KEY_VSYNC);

    private final int windowWidth;
    private final int windowHeight;
    private final boolean validation;
    private final float fovDegrees;
    private final float moveSpeed;
    private final float renderScale;
    private final boolean dlss;
    private final DlssMode dlssMode;
    private final boolean vsync;
    private final InputMap inputMap;
    private final List<String> warnings;

    private EngineSettings(Properties p) {
        List<String> warn = new ArrayList<>();
        windowWidth = intValue(p, KEY_WIDTH, 1280, 320, 7680, warn);
        windowHeight = intValue(p, KEY_HEIGHT, 720, 240, 4320, warn);
        validation = boolValue(p, KEY_VALIDATION, true, warn);
        fovDegrees = floatValue(p, KEY_FOV, 60f, 30f, 120f, warn);
        moveSpeed = floatValue(p, KEY_MOVE_SPEED, 5f, 0.1f, 200f, warn);
        renderScale = floatValue(p, KEY_RENDER_SCALE, 1f, 0.25f, 1f, warn);
        dlss = boolValue(p, KEY_DLSS, true, warn);
        dlssMode = dlssModeValue(p, warn);
        vsync = boolValue(p, KEY_VSYNC, true, warn);
        inputMap = inputValue(p, warn);
        for (String key : p.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(key) && !key.startsWith(INPUT_PREFIX)) {
                warn.add("알 수 없는 설정 '" + key + "' (오타인지 확인) → 무시");
            }
        }
        warnings = List.copyOf(warn);
    }

    /** 모두 기본값 */
    public static EngineSettings defaults() {
        return new EngineSettings(new Properties());
    }

    /**
     * 파일에서 읽는다. 없으면 기본값 파일을 만든다. 읽기/쓰기 실패도 경고만 남기고 기본값으로 진행한다.
     */
    public static EngineSettings load(Path file) {
        if (!Files.exists(file)) {
            EngineSettings defaults = defaults();
            try {
                defaults.save(file);
                Log.info("설정 파일이 없어 기본값으로 만들었습니다: " + file.toAbsolutePath());
            } catch (IOException e) {
                Log.warn("기본 설정 파일을 만들지 못했습니다(" + e.getMessage() + "). 기본값으로 진행");
            }
            return defaults;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("설정 파일을 읽지 못했습니다(" + e.getMessage() + "). 기본값으로 진행");
            return defaults();
        }
        EngineSettings settings = parse(text);
        String missing = settings.missingEntries(text);
        if (!missing.isEmpty()) {
            // 예전 버전이 만든 파일: 사용자가 쓴 내용은 그대로 두고, 새로 생긴 항목만 끝에 덧붙인다
            try {
                Files.writeString(file, text + (text.endsWith("\n") ? "" : "\n") + missing, StandardCharsets.UTF_8);
                Log.info("설정 파일에 새 항목을 추가했습니다: " + file.toAbsolutePath());
            } catch (IOException e) {
                Log.warn("설정 파일에 새 항목을 추가하지 못했습니다(" + e.getMessage() + "). 기본값으로 진행");
            }
        }
        return settings;
    }

    /**
     * text(파일 내용)에 없는 설정 항목을 "키=현재값" 줄로 돌려준다. 모두 있으면 빈 문자열.
     * 현재값 = 이 객체의 값이며, 파일에 없던 항목이므로 곧 기본값이다.
     */
    String missingEntries(String text) {
        Properties present = new Properties();
        try (Reader reader = new StringReader(text)) {
            present.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("StringReader 는 IOException 을 던지지 않음", e);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : toText().split("\n")) {
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq <= 0) {
                continue;
            }
            if (!present.containsKey(line.substring(0, eq))) {
                sb.append(line).append('\n');
            }
        }
        if (sb.isEmpty()) {
            return "";
        }
        return "\n# 새 버전에서 추가된 설정 (자동 추가, 설명은 이 파일을 지우고 다시 만들면 볼 수 있음)\n"
                + "# render.dlss: DLSS 사용, render.dlssMode: quality / balanced / performance / ultra_performance / dlaa,"
                + " render.vsync: 수직동기\n"
                + sb;
    }

    /** 문자열(파일 내용)에서 읽는다. 테스트에서도 사용. */
    public static EngineSettings parse(String text) {
        Properties p = new Properties();
        try (Reader reader = new StringReader(text)) {
            p.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("StringReader 는 IOException 을 던지지 않음", e);
        }
        return new EngineSettings(p);
    }

    /** 주석이 달린 사람이 읽기 좋은 형태로 저장한다. 임시 파일에 쓴 뒤 바꿔치기해서 중간에 꺼져도 파일이 깨지지 않는다. */
    public void save(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path dir = absolute.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.writeString(temp, toText(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Drift Engine 설정 파일 (UTF-8). 지우면 다음 실행 때 기본값으로 다시 만들어진다.\n");
        sb.append("# 실행 인자(--width, --height, --no-validation, --dlss, --no-dlss)가 있으면 그쪽이 우선한다.\n\n");
        sb.append("# 창 크기 (픽셀)\n");
        sb.append(KEY_WIDTH).append('=').append(windowWidth).append('\n');
        sb.append(KEY_HEIGHT).append('=').append(windowHeight).append('\n');
        sb.append("\n# Vulkan 검증 레이어 (개발 중에는 true 권장, Vulkan SDK 필요)\n");
        sb.append(KEY_VALIDATION).append('=').append(validation).append('\n');
        sb.append("\n# 수직동기 (true: 모니터 주사율에 맞춤, false: 최대한 빨리. 성능 측정 때는 false)\n");
        sb.append(KEY_VSYNC).append('=').append(vsync).append('\n');
        sb.append("\n# DLSS 사용 (기본 true). 이 PC 에서 DLSS 를 쓸 수 없으면 자동으로 아래 render.scale 방식으로 진행한다\n");
        sb.append("# 실행 인자 --no-dlss 로 끄고, --dlss 로 켤 수 있다. 실행 중 R 키: DLSS 품질 → 균형 → 성능 → Native\n");
        sb.append(KEY_DLSS).append('=').append(dlss).append('\n');
        sb.append("# DLSS 시작 모드: quality, balanced, performance, ultra_performance, dlaa\n");
        sb.append(KEY_DLSS_MODE).append('=').append(dlssMode.name().toLowerCase(Locale.ROOT)).append('\n');
        sb.append("\n# 렌더 배율 (0.25~1.0, DLSS 를 쓰지 않을 때). 1 보다 작으면 낮은 해상도로 그린 뒤 늘린다. 실행 중 R 키로 전환\n");
        sb.append(KEY_RENDER_SCALE).append('=').append(format(renderScale)).append('\n');
        sb.append("\n# 카메라 세로 시야각 (30~120도), 자유 이동 속도 (초당 미터)\n");
        sb.append(KEY_FOV).append('=').append(format(fovDegrees)).append('\n');
        sb.append(KEY_MOVE_SPEED).append('=').append(format(moveSpeed)).append('\n');
        sb.append("\n# 키 배치: input.행동=키 (쉼표로 여러 개, 비우면 끔)\n");
        sb.append("# 키 이름: A~Z, 0~9, SPACE, TAB, ENTER, LEFT_SHIFT, RIGHT_SHIFT, LEFT_CONTROL, RIGHT_CONTROL, LEFT_ALT, UP, DOWN, LEFT, RIGHT\n");
        for (InputAction action : InputAction.values()) {
            sb.append(INPUT_PREFIX).append(action.name()).append('=').append(inputMap.bindingText(action)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 값 읽기 (잘못되면 경고 + 기본값)

    private static int intValue(Properties p, String key, int def, int min, int max, List<String> warn) {
        String raw = p.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                warn.add(key + "=" + raw + " 범위 밖(" + min + "~" + max + ") → " + def);
                return def;
            }
            return v;
        } catch (NumberFormatException e) {
            warn.add(key + "=" + raw + " 정수가 아님 → " + def);
            return def;
        }
    }

    private static float floatValue(Properties p, String key, float def, float min, float max, List<String> warn) {
        String raw = p.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            float v = Float.parseFloat(raw.trim());
            if (Float.isNaN(v) || v < min || v > max) {
                warn.add(key + "=" + raw + " 범위 밖(" + format(min) + "~" + format(max) + ") → " + format(def));
                return def;
            }
            return v;
        } catch (NumberFormatException e) {
            warn.add(key + "=" + raw + " 숫자가 아님 → " + format(def));
            return def;
        }
    }

    private static boolean boolValue(Properties p, String key, boolean def, List<String> warn) {
        String raw = p.getProperty(key);
        if (raw == null) {
            return def;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("false")) {
            return v.equals("true");
        }
        warn.add(key + "=" + raw + " true/false 가 아님 → " + def);
        return def;
    }

    private static DlssMode dlssModeValue(Properties p, List<String> warn) {
        String raw = p.getProperty(KEY_DLSS_MODE);
        if (raw == null) {
            return DlssMode.QUALITY;
        }
        DlssMode mode = DlssMode.parse(raw);
        if (mode == null) {
            warn.add(KEY_DLSS_MODE + "=" + raw + " 알 수 없는 모드 (quality, balanced, performance, ultra_performance, dlaa) → quality");
            return DlssMode.QUALITY;
        }
        return mode;
    }

    private static InputMap inputValue(Properties p, List<String> warn) {
        Properties bindings = new Properties();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(INPUT_PREFIX)) {
                bindings.setProperty(key.substring(INPUT_PREFIX.length()), p.getProperty(key));
            }
        }
        try {
            return InputMap.fromProperties(bindings);
        } catch (IllegalArgumentException e) {
            warn.add("키 배치 오류: " + e.getMessage() + " → 키 배치 전체를 기본값으로");
            return InputMap.defaults();
        }
    }

    private static String format(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------ 값

    public int windowWidth() {
        return windowWidth;
    }

    public int windowHeight() {
        return windowHeight;
    }

    public boolean validation() {
        return validation;
    }

    public float fovDegrees() {
        return fovDegrees;
    }

    public float moveSpeed() {
        return moveSpeed;
    }

    public float renderScale() {
        return renderScale;
    }

    /** 수직동기 (기본 true) */
    public boolean vsync() {
        return vsync;
    }

    /** DLSS 사용 요청 (기본 true) */
    public boolean dlss() {
        return dlss;
    }

    /** DLSS 를 쓸 때 시작 모드 (기본 품질) */
    public DlssMode dlssMode() {
        return dlssMode;
    }

    public InputMap inputMap() {
        return inputMap;
    }

    /** 잘못된 값 목록 (비어 있으면 정상) */
    public List<String> warnings() {
        return warnings;
    }
}
