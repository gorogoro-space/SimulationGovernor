plugins {
    id("java-library")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // 単体テスト(jar には入らない)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// 検証用のビルド(gradlew build -Pverification)だけ、検証用のコマンドを入れる。リリースには使わない
val verification = project.hasProperty("verification")
if (verification) {
    sourceSets.main {
        java.srcDir("src/verification/java")
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        // 警告はすべて出す(0 件を保つ)
        options.compilerArgs.add("-Xlint:all")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        if (verification) {
            archiveClassifier.set("verification")
        }
    }
}
