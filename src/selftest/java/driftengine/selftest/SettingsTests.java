package driftengine.selftest;

import driftengine.core.EngineConfig;
import driftengine.core.EngineSettings;
import driftengine.upscale.DlssMode;
import driftengine.input.InputAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_I;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;

/** 설정 파일 테스트. 임시 폴더를 써서 실제 프로젝트 파일은 건드리지 않는다. */
final class SettingsTests {

    private SettingsTests() {
    }

    static void run() {
        test("파일이 없으면 기본값 파일 생성", SettingsTests::createsDefaults);
        test("저장 → 읽기 하면 같은 값", SettingsTests::roundTrip);
        test("잘못된 값은 경고 + 그 항목만 기본값", SettingsTests::invalidValues);
        test("오타 난 설정 이름은 경고", SettingsTests::unknownKey);
        test("키 배치를 설정 파일에서 변경", SettingsTests::inputBinding);
        test("잘못된 키 이름은 경고 + 키 배치 기본값", SettingsTests::badInput);
        test("실행 인자가 설정 파일보다 우선", SettingsTests::argsOverride);
        test("한글 주석이 있어도 읽힘 (UTF-8)", SettingsTests::utf8);
        test("DLSS 기본값: 켜짐 + 품질 모드", SettingsTests::dlssDefaults);
        test("DLSS 설정: 끄기, 모드 변경, 잘못된 모드는 경고", SettingsTests::dlssSettings);
        test("DLSS 실행 인자: --no-dlss / --dlss 가 설정 파일보다 우선", SettingsTests::dlssArgs);
        test("예전 설정 파일: 새 항목만 끝에 덧붙이고 기존 값은 유지", SettingsTests::upgradeOldFile);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("drift-settings");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void createsDefaults() {
        Path file = tempDir().resolve("settings.properties");
        EngineSettings s = EngineSettings.load(file);
        check(Files.exists(file), "파일 생성됨");
        check(s.windowWidth() == 1280 && s.windowHeight() == 720, "기본 창 크기");
        check(s.warnings().isEmpty(), "경고 없음: " + s.warnings());
    }

    private static void roundTrip() {
        EngineSettings original = EngineSettings.parse("window.width=1920\nwindow.height=1080\ncamera.fovDegrees=75.5\n");
        Path file = tempDir().resolve("s.properties");
        try {
            original.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EngineSettings loaded = EngineSettings.load(file);
        check(loaded.windowWidth() == 1920 && loaded.windowHeight() == 1080, "창 크기");
        check(Math.abs(loaded.fovDegrees() - 75.5f) < 1e-4f, "시야각 " + loaded.fovDegrees());
        check(loaded.warnings().isEmpty(), "경고 없음: " + loaded.warnings());
        check(loaded.inputMap().bindingText(InputAction.MOVE_FORWARD).equals("W"), "키 배치도 저장됨");
    }

    private static void invalidValues() {
        EngineSettings s = EngineSettings.parse("""
                window.width=abc
                window.height=99999
                render.validation=maybe
                camera.fovDegrees=170
                camera.moveSpeed=12
                """);
        check(s.windowWidth() == 1280, "너비 기본값");
        check(s.windowHeight() == 720, "높이 기본값");
        check(s.validation(), "검증 기본값");
        check(s.fovDegrees() == 60f, "시야각 기본값");
        check(s.moveSpeed() == 12f, "올바른 값은 그대로");
        check(s.warnings().size() == 4, "경고 4개: " + s.warnings());
    }

    private static void unknownKey() {
        EngineSettings s = EngineSettings.parse("window.widht=1920\n");
        check(s.warnings().size() == 1 && s.warnings().get(0).contains("window.widht"), "경고: " + s.warnings());
    }

    private static void inputBinding() {
        EngineSettings s = EngineSettings.parse("input.MOVE_FORWARD=I\n");
        check(s.inputMap().isActive(InputAction.MOVE_FORWARD, Set.of(GLFW_KEY_I)::contains), "I 로 앞으로");
        check(!s.inputMap().isActive(InputAction.MOVE_FORWARD, Set.of(GLFW_KEY_W)::contains), "W 는 해제");
    }

    private static void badInput() {
        EngineSettings s = EngineSettings.parse("input.MOVE_FORWARD=WHAT\n");
        check(s.warnings().size() == 1, "경고 1개: " + s.warnings());
        check(s.inputMap().isActive(InputAction.MOVE_FORWARD, Set.of(GLFW_KEY_W)::contains), "기본 W 유지");
    }

    private static void argsOverride() {
        EngineSettings s = EngineSettings.parse("window.width=1600\nwindow.height=900\nrender.validation=true\n");
        EngineConfig noArgs = EngineConfig.fromArgs(new String[0], s);
        check(noArgs.width() == 1600 && noArgs.height() == 900, "설정 파일 값 사용");
        EngineConfig withArgs = EngineConfig.fromArgs(new String[]{"--width=800", "--no-validation"}, s);
        check(withArgs.width() == 800, "인자 우선");
        check(withArgs.height() == 900, "인자 없는 항목은 설정 파일 값");
        check(!withArgs.validation(), "--no-validation 우선");
    }

    private static void utf8() {
        Path file = tempDir().resolve("s.properties");
        try {
            Files.writeString(file, "# 한글 주석\nwindow.width=1024\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EngineSettings s = EngineSettings.load(file);
        check(s.windowWidth() == 1024 && s.warnings().isEmpty(), "읽기: " + s.warnings());
    }

    private static void dlssDefaults() {
        EngineSettings s = EngineSettings.defaults();
        check(s.dlss(), "기본 DLSS 켜짐");
        check(s.dlssMode() == DlssMode.QUALITY, "기본 품질");
        check(EngineConfig.fromArgs(new String[0], s).dlss(), "인자 없이 실행해도 DLSS 요청");
        EngineSettings reloaded = EngineSettings.parse(s.toText());
        check(reloaded.dlss() && reloaded.dlssMode() == DlssMode.QUALITY && reloaded.warnings().isEmpty(),
                "기본 파일 다시 읽기: " + reloaded.warnings());
    }

    private static void dlssSettings() {
        EngineSettings off = EngineSettings.parse("render.dlss=false\nrender.dlssMode=performance\n");
        check(!off.dlss(), "끄기");
        check(off.dlssMode() == DlssMode.PERFORMANCE, "성능 모드");
        check(off.warnings().isEmpty(), "경고 없음: " + off.warnings());
        check(EngineSettings.parse(off.toText()).dlssMode() == DlssMode.PERFORMANCE, "저장 후에도 성능 모드");
        EngineSettings bad = EngineSettings.parse("render.dlssMode=ultra\n");
        check(bad.dlssMode() == DlssMode.QUALITY, "잘못된 모드 → 품질");
        check(bad.warnings().size() == 1 && bad.warnings().get(0).contains("render.dlssMode"), "경고: " + bad.warnings());
    }

    private static void dlssArgs() {
        EngineSettings on = EngineSettings.defaults();
        check(!EngineConfig.fromArgs(new String[]{"--no-dlss"}, on).dlss(), "--no-dlss 로 끔");
        EngineSettings off = EngineSettings.parse("render.dlss=false\n");
        check(!EngineConfig.fromArgs(new String[0], off).dlss(), "설정 파일에서 끔");
        check(EngineConfig.fromArgs(new String[]{"--dlss"}, off).dlss(), "--dlss 로 켬");
    }

    private static void upgradeOldFile() {
        Path file = tempDir().resolve("settings.properties");
        String old = "# 내 메모\nwindow.width=1600\ncamera.moveSpeed=9\n";
        try {
            Files.writeString(file, old, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EngineSettings s = EngineSettings.load(file);
        check(s.windowWidth() == 1600 && s.moveSpeed() == 9f && s.dlss(), "기존 값 + DLSS 기본");
        String after;
        try {
            after = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        check(after.startsWith(old), "기존 내용(메모 포함) 그대로");
        check(after.contains("render.dlss=true") && after.contains("render.dlssMode=quality"), "DLSS 항목 추가");
        check(!after.substring(old.length()).contains("window.width="), "이미 있던 항목은 다시 안 씀");
        EngineSettings again = EngineSettings.load(file);
        try {
            check(Files.readString(file, StandardCharsets.UTF_8).equals(after), "두 번째 실행에는 변화 없음");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        check(again.warnings().isEmpty(), "경고 없음: " + again.warnings());
    }
}
