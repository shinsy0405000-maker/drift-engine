// 스크립트 안에서 "java" 는 Java 플러그인 설정(java { })을 가리키므로, JDK 클래스는 import 로 가져온다.
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    application
}

group = "driftengine"
version = "0.2.0-stage2"

// ---------------------------------------------------------------------------
// LWJGL 버전과 네이티브 분류자(classifier)
// lwjgl / lwjgl-glfw 는 OS별 네이티브(.dll/.so)가 필요하다.
// lwjgl-vulkan 은 Windows/Linux 에서 네이티브가 없다(드라이버의 Vulkan 로더를 사용).
// macOS 에서만 MoltenVK 네이티브가 필요하다.
// ---------------------------------------------------------------------------
val lwjglVersion = "3.4.3"

val osName: String = System.getProperty("os.name").lowercase()
val osArch: String = System.getProperty("os.arch").lowercase()
val isArm64 = osArch == "aarch64" || osArch == "arm64"

val lwjglNatives: String = when {
    osName.contains("win") -> if (isArm64) "natives-windows-arm64" else "natives-windows"
    osName.contains("mac") -> if (isArm64) "natives-macos-arm64" else "natives-macos"
    else -> if (isArm64) "natives-linux-arm64" else "natives-linux"
}
val isMac = osName.contains("mac")

// ---------------------------------------------------------------------------
// 물리 엔진: Jolt Physics 의 JVM 바인딩 jolt-jni (MIT). C++ 엔진이지만 바인딩과 네이티브가 미리 빌드되어
// Maven Central 에 있으므로 이 프로젝트에 C++ 코드가 늘지 않는다.
// 6.2.0 (2026-10-14): 최신 7.0.0 은 나온 지 이틀이라 바로 앞 버전으로 고정.
// 네이티브 분류자: Release(최적화) + Sp(단정밀도). 큰 월드는 기준점 이동(floating origin)으로 정밀도 유지.
// ---------------------------------------------------------------------------
val joltJniVersion = "6.2.0"
val joltPlatform: String = when {
    osName.contains("win") -> "Windows64"
    osName.contains("mac") -> if (isArm64) "MacOSX_ARM64" else "MacOSX64"
    else -> if (isArm64) "Linux_ARM64" else "Linux64"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl:lwjgl")        // 코어: 메모리 관리, 네이티브 호출
    implementation("org.lwjgl:lwjgl-glfw")   // 창 생성, 입력, Vulkan 서피스
    implementation("org.lwjgl:lwjgl-vulkan") // Vulkan 바인딩

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    if (isMac) {
        runtimeOnly("org.lwjgl:lwjgl-vulkan::$lwjglNatives") // MoltenVK
    }

    implementation("com.github.stephengold:jolt-jni-$joltPlatform:$joltJniVersion")          // Java API
    runtimeOnly("com.github.stephengold:jolt-jni-$joltPlatform:$joltJniVersion:ReleaseSp")  // 네이티브 라이브러리
}

// 의존성 jar 를 내려받고 경로를 출력한다 (클라우드 쪽 컴파일 확인용 파일 위치 확인):  ./gradlew resolveDeps
tasks.register("resolveDeps") {
    group = "help"
    description = "런타임 의존성을 내려받고 jar 경로를 출력한다."
    val files = configurations.runtimeClasspath.get()
    doLast {
        files.forEach { println(it.absolutePath) }
    }
}

java {
    toolchain {
        // LWJGL 3.4 는 JDK 25+ 에서 FFM(Foreign Function & Memory) 백엔드를 기본 사용한다.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

application {
    mainClass = "driftengine.Main"
    applicationDefaultJvmArgs = buildList {
        // JDK 24+ 의 네이티브 접근 경고를 없애고, 향후 기본 차단에 대비한다.
        add("--enable-native-access=ALL-UNNAMED")
        // LWJGL 내부의 sun.misc.Unsafe 사용 경고(JDK 24+) 숨김. 동작에는 영향 없음.
        add("--sun-misc-unsafe-memory-access=allow")
        if (isMac) add("-XstartOnFirstThread") // GLFW 는 macOS 에서 메인 스레드 필수
    }
}

// ---------------------------------------------------------------------------
// 자체 테스트: src/selftest/java  →  ./gradlew selfTest
// 외부 테스트 라이브러리(JUnit 등) 없이 main 함수로 검사한다. GPU 가 필요 없는 계산만 대상.
// (Gradle 9 의 test 태스크는 테스트 프레임워크 없이 테스트 소스가 있으면 실패하므로 별도 소스셋을 쓴다)
// ---------------------------------------------------------------------------
val selftest: SourceSet = sourceSets.create("selftest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[selftest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[selftest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val selfTest = tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "수학/카메라 자체 테스트를 실행한다."
    classpath = selftest.runtimeClasspath
    mainClass = "driftengine.selftest.SelfTestMain"
    javaLauncher = javaToolchains.launcherFor(java.toolchain) // Gradle 실행 JVM 이 아니라 JDK 25 로 실행
    jvmArgs("-Dstdout.encoding=UTF-8")
}
tasks.named("check") {
    dependsOn(selfTest)
}

// 소리 샘플: 차마다 제자리 회전수 올리기 + 주행 WAV 를 build/sound-samples 에 만든다 (소리 장치 불필요)
tasks.register<JavaExec>("soundSamples") {
    group = "verification"
    description = "차마다 엔진·터보·타이어 소리 샘플 WAV 를 만든다."
    classpath = selftest.runtimeClasspath
    mainClass = "driftengine.selftest.SoundSamples"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("-Dstdout.encoding=UTF-8")
    args(layout.buildDirectory.dir("sound-samples").get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------
// 셰이더 컴파일: src/main/shaders/*.vert|frag  →  build/generated/shaders/shaders/*.spv
// Vulkan SDK 의 glslc 를 사용한다. 결과물은 클래스패스 /shaders/ 아래로 들어간다.
// ---------------------------------------------------------------------------
abstract class CompileShaders : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** #include 로 끌어 쓰는 파일 (*.glsl). 따로 컴파일하지 않고, 바뀌면 다시 컴파일하도록 입력에만 넣는다 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includes: ConfigurableFileCollection

    @get:Input
    abstract val glslc: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun compile() {
        val outDir = outputDir.get().asFile.resolve("shaders")
        outDir.deleteRecursively()
        outDir.mkdirs()
        sources.files.sortedBy { it.name }.forEach { src ->
            val out = File(outDir, src.name + ".spv")
            try {
                execOps.exec {
                    commandLine(glslc.get(), "--target-env=vulkan1.3", "-O", src.absolutePath, "-o", out.absolutePath)
                }
            } catch (e: Exception) {
                throw GradleException(
                    "셰이더 컴파일 실패: ${src.name}\n" +
                    "  glslc 경로: ${glslc.get()}\n" +
                    "  Vulkan SDK 가 설치되어 있는지, IntelliJ/Gradle 을 SDK 설치 후 재시작했는지 확인하세요.", e
                )
            }
            logger.lifecycle("셰이더 컴파일: ${src.name} -> ${out.name}")
        }
    }
}

/** VULKAN_SDK 환경 변수 → C:\VulkanSDK\<최신 버전> → PATH 순서로 glslc 를 찾는다. */
fun findGlslc(): String {
    val exe = if (osName.contains("win")) "glslc.exe" else "glslc"
    System.getenv("VULKAN_SDK")?.let { sdk ->
        listOf("Bin", "bin").map { File(sdk, "$it/$exe") }.firstOrNull { it.isFile }?.let { return it.absolutePath }
    }
    if (osName.contains("win")) {
        File("C:/VulkanSDK").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { File(it, "Bin/$exe") }
            ?.firstOrNull { it.isFile }
            ?.let { return it.absolutePath }
    }
    return exe // PATH 에서 찾도록 맡긴다
}

val compileShaders = tasks.register<CompileShaders>("compileShaders") {
    group = "build"
    description = "GLSL 셰이더를 SPIR-V 로 컴파일한다."
    sources.from(fileTree("src/main/shaders") { include("**/*.vert", "**/*.frag", "**/*.comp") })
    includes.from(fileTree("src/main/shaders") { include("**/*.glsl") })
    glslc = findGlslc()
    outputDir = layout.buildDirectory.dir("generated/shaders")
}

sourceSets.main {
    resources.srcDir(compileShaders) // processResources 가 compileShaders 에 자동으로 의존하게 된다
}

tasks.named<JavaExec>("run") {
    // 예: ./gradlew run --args="--no-validation --frames=300"
    workingDir = layout.projectDirectory.asFile
    // 렌더 설정 넘기기: ./gradlew run -Ddrift.ao=off -Ddrift.shadowSize=2048  (drift. 로 시작하는 것만)
    systemProperties(providers.systemPropertiesPrefixedBy("drift.").get())
    // 로그 한글: 게임은 UTF-8 로 내보낸다 (Gradle 이 UTF-8 로 읽음). 터미널은 chcp 65001 이면 바로 보인다
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// ---------------------------------------------------------------------------
// Streamline(DLSS) 준비:  ./gradlew setupStreamline
// GitHub 릴리스 zip 의 SHA-256 을 공개된 값과 비교한 뒤, 필요한 파일만 꺼낸다.
//   실행용 DLL        → natives/streamline/
//   헤더 + 링크용 lib → native/external/streamline-sdk/   (C++ 브리지 빌드용, git 에서 제외)
// zip 위치 기본값: 사용자 Downloads 폴더. 다른 곳이면 -PstreamlineZip=경로
// ---------------------------------------------------------------------------
val streamlineVersion = "2.14.16"
// https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.14.16 에 표시된 streamline-sdk-v2.14.16.zip 의 값
val streamlineSha256 = "32041e51db0cab709a271b72a352281b690c2abbd669a827809401a5dd23956b"

abstract class SetupStreamline : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zip: RegularFileProperty

    @get:Input
    abstract val sha256: Property<String>

    /** zip 의 bin/x64/ 에서 꺼낼 파일 */
    @get:Input
    abstract val runtimeFiles: ListProperty<String>

    @get:OutputDirectory
    abstract val runtimeDir: DirectoryProperty

    @get:OutputDirectory
    abstract val sdkDir: DirectoryProperty

    @TaskAction
    fun setup() {
        val zipFile = zip.get().asFile
        val md = MessageDigest.getInstance("SHA-256")
        zipFile.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256.get(), ignoreCase = true)) {
            throw GradleException(
                "Streamline zip 의 SHA-256 이 공개된 값과 다릅니다 (손상되었거나 다른 파일).\n" +
                "  파일: $zipFile\n  기대: ${sha256.get()}\n  실제: $actual"
            )
        }
        logger.lifecycle("SHA-256 일치: ${zipFile.name}")

        val runtime = runtimeDir.get().asFile.apply { mkdirs() }
        val sdk = sdkDir.get().asFile.apply { mkdirs() }
        val wanted = runtimeFiles.get().toSet()
        val found = mutableSetOf<String>()
        var sdkCount = 0

        ZipFile(zipFile).use { z ->
            for (entry in z.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                val inBin = name.removePrefix("bin/x64/")
                val target: File = when {
                    name.startsWith("bin/x64/") && inBin in wanted -> File(runtime, inBin).also { found += inBin }
                    name.startsWith("include/") || name == "lib/x64/sl.interposer.lib" || name == "license.txt" ->
                        File(sdk, name).also { sdkCount++ }
                    else -> continue
                }
                // zip 안의 경로가 대상 폴더 밖을 가리키지 못하게 막는다
                val base = if (target.startsWith(runtime)) runtime else sdk
                if (!target.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
                    throw GradleException("zip 안에 잘못된 경로: $name")
                }
                target.parentFile.mkdirs()
                z.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        val missing = wanted - found
        if (missing.isNotEmpty()) {
            throw GradleException("zip 의 bin/x64/ 에 없는 파일: $missing")
        }
        if (!File(sdk, "include/sl.h").isFile || !File(sdk, "lib/x64/sl.interposer.lib").isFile) {
            throw GradleException("zip 에 include/sl.h 또는 lib/x64/sl.interposer.lib 가 없습니다.")
        }
        logger.lifecycle("실행용 파일 ${found.size}개 → $runtime")
        logger.lifecycle("헤더/라이브러리 ${sdkCount}개 → $sdk")
    }
}

val setupStreamline = tasks.register<SetupStreamline>("setupStreamline") {
    group = "dlss"
    description = "Streamline SDK zip 을 확인하고 필요한 파일을 꺼낸다."
    val zipPath = providers.gradleProperty("streamlineZip")
        .orElse(System.getProperty("user.home") + "/Downloads/streamline-sdk-v$streamlineVersion.zip")
    zip = layout.file(zipPath.map { File(it) })
    sha256 = providers.gradleProperty("streamlineSha256").orElse(streamlineSha256)
    runtimeFiles = listOf("sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "nvngx_dlss.dll", "nvngx_dlss.license.txt")
    runtimeDir = layout.projectDirectory.dir("natives/streamline")
    sdkDir = layout.projectDirectory.dir("native/external/streamline-sdk")
}

// ---------------------------------------------------------------------------
// C++ 브리지 빌드:  ./gradlew buildStreamlineBridge   (Windows, Visual Studio Build Tools 필요)
// vswhere 로 설치된 C++ 빌드 도구를 찾고, 거기 포함된 CMake 로 빌드한다.
// 결과 drift_sl_bridge.dll 은 CMake 가 natives/streamline/ 으로 복사한다.
// ---------------------------------------------------------------------------
abstract class BuildStreamlineBridge : DefaultTask() {
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val cmakeBuildDir: DirectoryProperty

    @get:Internal
    abstract val sdkDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            throw GradleException("Streamline 브리지는 Windows 에서만 빌드할 수 있습니다.")
        }
        val cmake = findCmake()
        logger.lifecycle("CMake: $cmake")
        execOps.exec {
            commandLine(
                cmake, "-S", sourceDir.get().asFile.absolutePath, "-B", cmakeBuildDir.get().asFile.absolutePath,
                "-A", "x64", "-DSTREAMLINE_SDK=" + sdkDir.get().asFile.absolutePath.replace('\\', '/')
            )
        }
        execOps.exec {
            commandLine(cmake, "--build", cmakeBuildDir.get().asFile.absolutePath, "--config", "Release")
        }
    }

    private fun findCmake(): String {
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:/Program Files (x86)"
        val vswhere = File(programFilesX86, "Microsoft Visual Studio/Installer/vswhere.exe")
        if (!vswhere.isFile) {
            throw GradleException(
                "Visual Studio Build Tools 가 없습니다. vs_BuildTools.exe 로 'C++를 사용한 데스크톱 개발' 을 설치하세요."
            )
        }
        val out = ByteArrayOutputStream()
        execOps.exec {
            commandLine(
                vswhere.absolutePath, "-latest", "-products", "*", "-utf8",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"
            )
            standardOutput = out
            isIgnoreExitValue = true
        }
        val install = out.toString(Charsets.UTF_8).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: throw GradleException(
                "C++ 빌드 도구(MSVC x64)가 설치되어 있지 않습니다. Visual Studio Installer 에서 'C++를 사용한 데스크톱 개발' 을 선택하세요."
            )
        val bundled = File(install, "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe")
        if (bundled.isFile) return bundled.absolutePath
        logger.warn("Visual Studio 에 포함된 CMake 가 없어 PATH 의 cmake 를 사용합니다. ($install)")
        return "cmake"
    }
}

tasks.register<BuildStreamlineBridge>("buildStreamlineBridge") {
    group = "dlss"
    description = "C++ Streamline 브리지(drift_sl_bridge.dll)를 빌드한다."
    dependsOn(setupStreamline)
    sourceDir = layout.projectDirectory.dir("native/streamline-bridge")
    cmakeBuildDir = layout.projectDirectory.dir("native/build")
    sdkDir = setupStreamline.flatMap { it.sdkDir }
}

// ---------------------------------------------------------------------------
// 언어별 코드 비중 추적:  ./gradlew languageStats
// 빈 줄을 제외한 줄 수를 센다. 목표: Java / (Java + C/C++) >= 80%
// ---------------------------------------------------------------------------
val languagePatterns = linkedMapOf(
    "Java" to listOf("src/**/*.java"),
    "C/C++ (native)" to listOf("native/**/*.c", "native/**/*.cpp", "native/**/*.h", "native/**/*.hpp"),
    "GLSL (shader)" to listOf("src/**/*.vert", "src/**/*.frag", "src/**/*.comp", "src/**/*.glsl"),
    "Gradle (build)" to listOf("*.gradle.kts", "native/**/CMakeLists.txt"),
)

tasks.register("languageStats") {
    group = "reporting"
    description = "Java 와 네이티브 코드의 비중을 출력한다."

    val trees = languagePatterns.mapValues { (_, patterns) ->
        fileTree(layout.projectDirectory) {
            include(patterns)
            exclude("**/build/**", "**/.gradle/**", "native/external/**")
        }
    }

    doLast {
        val lines = trees.mapValues { (_, tree) ->
            tree.files.sumOf { file -> file.readLines().count { it.isNotBlank() } }
        }
        val java = lines.getValue("Java")
        val native = lines.getValue("C/C++ (native)")
        val shader = lines.getValue("GLSL (shader)")

        println("언어별 코드 줄 수 (빈 줄 제외)")
        lines.forEach { (lang, n) -> println("  %-16s %6d".format(lang, n)) }

        fun pct(part: Int, whole: Int) = if (whole == 0) 100.0 else part * 100.0 / whole
        val javaVsNative = pct(java, java + native)
        val javaVsAllCode = pct(java, java + native + shader)
        println("  Java / (Java + C/C++)        = %.1f%%".format(javaVsNative))
        println("  Java / (Java + C/C++ + GLSL) = %.1f%%".format(javaVsAllCode))
        if (javaVsNative < 80.0) {
            println("  [경고] Java 비중이 목표(80%) 미만입니다.")
        }
    }
}

// ---------------------------------------------------------------------------
// 클라우드 확인용 Linux 네이티브:  ./gradlew cloudNatives
// 개발 도우미(Claude)가 GPU 없는 Linux 환경(소프트웨어 Vulkan)에서 화면을 찍어 확인할 때 쓰는 jar 를
// build/cloud-natives 에 모은다. 게임 실행에는 쓰이지 않는다 (Windows 에서는 Windows 네이티브를 씀).
// ---------------------------------------------------------------------------
val cloudNatives: Configuration = configurations.create("cloudNatives") {
    isTransitive = false
}

dependencies {
    cloudNatives("org.lwjgl:lwjgl:$lwjglVersion:natives-linux")
    cloudNatives("org.lwjgl:lwjgl-glfw:$lwjglVersion:natives-linux")
    cloudNatives("com.github.stephengold:jolt-jni-Linux64:$joltJniVersion")
    cloudNatives("com.github.stephengold:jolt-jni-Linux64:$joltJniVersion:ReleaseSp")
}

tasks.register<Copy>("cloudNatives") {
    group = "help"
    description = "클라우드(Linux) 화면 확인용 네이티브 jar 를 build/cloud-natives 에 모은다."
    from(cloudNatives)
    into(layout.buildDirectory.dir("cloud-natives"))
    doLast {
        println("모은 파일: " + destinationDir.listFiles()?.joinToString { it.name })
    }
}
