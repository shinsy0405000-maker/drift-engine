plugins {
    // 빌드 전용 플러그인: 로컬에 JDK 25가 없으면 Gradle이 자동으로 내려받는다.
    // 게임 런타임에는 포함되지 않는다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "drift-engine"
