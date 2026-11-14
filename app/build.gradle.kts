import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 제출 APK 서명 키. 키 경로와 비밀번호는 저장소에 올리지 않는 keystore.properties에 있습니다.
// 파일이 없으면 release는 서명 없이 나옵니다.
val releaseKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseKey = releaseKey.getProperty("storeFile") != null

// 제출판(release)만 앱 ID를 바꿉니다. 검증 빌드(debug, verify)는 시연 스크립트가 쓰는 com.example.pleosapp 그대로입니다.
val releaseAppId = "io.github.skdddfhg123.gureumi"

android {
    namespace = "com.example.pleosapp"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        // 검증 빌드의 앱 ID입니다. 제출판은 아래 androidComponents에서 releaseAppId로 바꿉니다.
        applicationId = "com.example.pleosapp"
        // Pleos Connect 요구사항: 차량 사이버보안(서명 키 로테이션·리니지) 때문에 API 28 이상
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
                // v2에 더해 v3(키 교체 계보)도 붙입니다. minSdk 28이라 v1은 필요 없습니다.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        // 시간 지표를 재는 빌드입니다. 릴리스 코드에 debug의 계측을 더합니다. 제출하지 않습니다.
        create("verify") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets {
        getByName("verify") {
            java.srcDir("src/debug/java")
            res.srcDir("src/debug/res")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Gleo 스키마의 packageName을 제출판 앱 ID로 바꾼 사본을 release 자산에 넣습니다. main의 schema.json은 그대로입니다.
abstract class ReleaseGleoSchema : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:Input abstract val appId: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val text = source.get().asFile.readText(Charsets.UTF_8)
        val replaced = text.replace("\"packageName\": \"com.example.pleosapp\"", "\"packageName\": \"${appId.get()}\"")
        check(replaced != text) { "schema.json에서 packageName을 찾지 못했습니다" }
        outputDir.get().asFile.resolve("schema.json").writeText(replaced, Charsets.UTF_8)
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set(releaseAppId)
        val schema = tasks.register<ReleaseGleoSchema>("${variant.name}GleoSchema") {
            source.set(layout.projectDirectory.file("src/main/assets/schema.json"))
            appId.set(releaseAppId)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(schema, ReleaseGleoSchema::outputDir)
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    // 기술 검증 콘솔 전용. 릴리스 APK에는 들어가지 않습니다.
    debugImplementation(libs.pleos.stt)
    debugImplementation(libs.pleos.navi)
    "verifyImplementation"(libs.pleos.stt)
    "verifyImplementation"(libs.pleos.navi)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
