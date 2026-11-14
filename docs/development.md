# 개발 환경

구름이를 빌드해 Pleos Connect 에뮬레이터에서 돌리는 방법입니다. [README](../README.md)의 '실행하기'를 한 단계 더 풀었습니다. 명령은 Windows PowerShell 기준입니다. macOS와 Linux에서도 Gradle과 adb 명령은 같고, `.ps1` 스크립트만 Windows용입니다.

## 필요한 것

- JDK 17. 최근 Android Studio에 든 JDK 25로는 Gradle 8.10.2가 돌지 않습니다. Android Studio를 쓰면 Settings의 Build Tools, Gradle에서 Gradle JDK도 17로 맞춥니다.
- Android SDK. platform-tools, emulator, build-tools 35.0.0, 플랫폼 34와 35를 씁니다.
- [Pleos Connect 에뮬레이터](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) v2.1. 시스템 이미지는 `system-images;android-34;pleos_car_emulator;x86_64`입니다.
- 인터넷. 첫 빌드 때 Pleos 메이븐 저장소에서 차량 SDK를 받습니다.

환경 변수 `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_AVD_HOME`을 잡고, `Path`에 platform-tools와 emulator 폴더를 더합니다. 에뮬레이터 이미지는 SDK Manager의 SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`을 더해 받습니다.

빌드 구성은 Pleos 샘플 앱 기준에 맞췄습니다. Gradle 8.10.2, Android Gradle Plugin 8.8.2, Kotlin 2.0.0입니다. minSdk는 28, targetSdk는 35이고, Android Studio가 업그레이드를 권해도 그대로 둡니다.

차량 SDK는 Vehicle 2.3.2와 TextToSpeech 3.3.1입니다. SpeechToText 3.3.1과 NaviHelper 3.0.5는 검증 빌드(debug)에만 들어갑니다.

## 에뮬레이터 켜기

AVD는 Android Studio의 Device Manager에서 위 시스템 이미지로 만들고, 이름을 `Pleos_Connect_v2.1`로 둡니다. 콜드 부트(Cold boot)로 켜고 RAM은 4 GB, 그래픽은 소프트웨어(`swiftshader_indirect`)로 둡니다. 빠른 부팅(Quick boot)으로 켜면 차량 신호를 받지 못합니다. 그래픽 `host` 모드는 개발 PC에서 드라이버 오류로 꺼진 적이 있어 쓰지 않습니다.

```powershell
.\scripts\start-emulator.ps1              # 창을 띄워 켬. 창 없이 켜려면 -Headless
adb wait-for-device
adb shell getprop sys.boot_completed      # 1이면 부팅 끝
```

스크립트는 SDK를 `C:\Android\Sdk`, AVD를 `C:\Android\avd`에서 찾습니다. 다른 곳에 두었으면 스크립트의 두 경로를 고칩니다. 부팅이 끝나도 1분쯤은 차량 신호가 늦게 들어오니, 시험은 그 뒤에 합니다. 에뮬레이터 시간대는 미국 서부라서 한국 낮에 밤 장면이 뜹니다. 켠 뒤 `adb shell cmd alarm set-timezone Asia/Seoul`로 바꿉니다.

## 빌드와 설치

```powershell
$env:JAVA_HOME = 'C:\Android\jdk17'       # JDK 17이 있는 곳
.\gradlew.bat installDebug                # Android Studio의 Run으로 설치해도 됨
adb shell am start -n com.example.pleosapp/.ui.home.HomeActivity
# 권한을 미리 줄 때. 에뮬레이터의 Android 사용자는 10이라 --user 10을 붙임
adb shell pm grant --user 10 com.example.pleosapp android.permission.POST_NOTIFICATIONS
adb shell pm grant --user 10 com.example.pleosapp android.car.permission.CAR_SPEED
```

제출 APK는 앱 ID가 달라 검증 빌드와 따로 깔립니다. 둘을 함께 두면 두 앱이 같은 신호에 답하니, 시험할 때는 하나만 둡니다.

```powershell
adb install apk\gureumi-dev_gureumi_release.apk
adb shell am start -n io.github.skdddfhg123.gureumi/com.example.pleosapp.ui.home.HomeActivity
```

- 앱을 한 번 열면 차량 신호를 받는 서비스가 뜹니다. 그 뒤로는 에뮬레이터를 켤 때와 다시 설치할 때 저절로 뜹니다.
- 처음 열면 구름이가 세 마디로 인사한 뒤 차 속도 권한을 묻습니다. 알림 권한은 첫 도착 뒤 집으로 돌아왔을 때 묻습니다. 속도 권한은 "위치 정보"로 뜹니다. Android Automotive가 차 속도를 위치 권한 묶음에 넣기 때문입니다. 앱은 위치를 읽지 않습니다.

빌드는 세 가지입니다. 제출 빌드는 `.\gradlew.bat assembleRelease`로 만듭니다. 프로젝트 맨 위에 `keystore.properties`가 있으면 그 키로 서명한 `app-release.apk`가, 없으면 서명 없는 `app-release-unsigned.apk`가 나옵니다. 키 파일은 저장소에 없습니다.

| 빌드 | Gradle 이름 | 앱 ID | 담긴 것 |
|---|---|---|---|
| 검증 빌드 | `debug` | `com.example.pleosapp` | 검증 콘솔, 시험 입력, 자세한 로그 |
| 제출 빌드 | `release` | `io.github.skdddfhg123.gureumi` | 시험 입력 없음. 로그는 실패 줄만 남김. v3 서명 |
| 측정 빌드 | `verify` | `com.example.pleosapp` | 제출 빌드 코드에 검증 빌드의 계측과 시험 입력을 더함. 성능을 잴 때만 씀. 이름은 verify지만 검증 빌드(debug)와 다름 |

## Virtual Console로 차량 상태 바꾸기

```powershell
adb forward tcp:8088 tcp:8088
Start-Process 'http://127.0.0.1:8088/'
```

- 브라우저에서 기어, 페달, 깜빡이, 핸들, 경고등, 문을 바꿉니다. 화면 설명은 [Virtual Console 안내](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)에 있습니다.
- 접속이 안 되면 `adb devices`로 연결을 보고 `adb forward`를 다시 합니다.
- D로 바꾸면 플랫폼이 앱 화면을 가립니다. P에 3초 머물면 트립(출발~도착 한 번)이 끝나고 도착 일기가 뜹니다.

에뮬레이터가 주행 중에 꺼진 적이 있어, 시연 트립은 15초 안에 끝나는 스크립트로 돌립니다. Virtual Console 페이지에서 F12로 개발자 도구를 엽니다. 콘솔에 `scripts/vc-trip-15s.js`를 통째로 붙여 넣습니다. 약 12초 동안 출발, 살살 멈추기, 급제동, 도착이 이어집니다. 15초쯤 뒤 `window.__trip.log`로 단계별 기록을 봅니다.

앱이 받은 신호와 이벤트는 검증 빌드의 로그 태그 `Spike`로 봅니다. 기어와 깜빡이 같은 원래 값까지 보려면 검증 콘솔의 '신호 로그' 단추를 누릅니다. 제출 빌드는 태그 `Gureumi`로 실패 줄만 값 없이 남깁니다.

```powershell
adb logcat -s Spike:I                            # 신호(SIG), 이벤트(EVT), 15초마다 HEARTBEAT
adb logcat -d -s Spike:I | Select-String 'EVT'   # 지금까지의 이벤트만
```

## 검증 빌드의 시험 입력

Virtual Console에는 자리별 안전벨트를 바꾸는 칸이 없습니다. 에뮬레이터의 안전벨트 값도 바뀌지 않습니다. 그래서 검증 빌드는 adb로 시험 입력을 받고, 제출 빌드는 받지 않습니다. 검증 콘솔을 한 번 연 뒤로는 브로드캐스트로 넣습니다. 브로드캐스트는 홈을 가리지 않아 반응을 바로 볼 수 있고, 콘솔은 닫아도 됩니다.

```powershell
adb shell am start -n com.example.pleosapp/.MainActivity    # 검증 콘솔을 한 번 엶
# 아기 정령을 하나 들임
adb shell am broadcast --user 10 -a com.example.pleosapp.SPIKE --es spike family_add --es preset som
# 왼쪽 뒷자리에 사람이 탐. 안전벨트를 안 맨 채 문이 닫히면 6초 뒤 매 달라고 함
adb shell am broadcast --user 10 -a com.example.pleosapp.SPIKE --es spike rear_belt --es seat left --ez buckled false
adb shell am broadcast --user 10 -a com.example.pleosapp.SPIKE --es spike rear_door --es seat left --ez open true
adb shell am broadcast --user 10 -a com.example.pleosapp.SPIKE --es spike rear_door --es seat left --ez open false
# 안전벨트를 맴. 아기 정령이 깨어남
adb shell am broadcast --user 10 -a com.example.pleosapp.SPIKE --es spike rear_belt --es seat left --ez buckled true
# 창밖을 밤과 비로 고정함. time과 sky를 빼고 보내면 실제 시각과 날씨로 돌아감
adb shell am broadcast --user 10 -a com.example.pleosapp.SPIKE --es spike scene --es time night --es sky rain
```

- `seat`는 `front`(조수석), `left`, `center`, `right`(뒷자리)이고, 빼면 `left`입니다. `time`은 `morning`, `day`, `evening`, `night`, `sky`는 `clear`, `rain`, `snow`, `fog`입니다.
- 안전벨트는 풀린 값을 먼저 보내야 매는 순간으로 봅니다. 풀린 값 없이 `true`가 오면 이미 맨 것으로 보고 조용히 깨웁니다.
- `preset`은 `saeteol`(새싹), `som`(도토리), `yangtte`(개나리), `angae`(라일락)입니다. 값은 처음 정한 이름이 남은 것이라 화면 이름과 다릅니다. 모두 지우려면 `--es spike family_clear`를 보냅니다.
- 앱 프로세스가 다시 뜨면 검증 콘솔을 한 번 더 엽니다. 창밖 고정도 이때 풀립니다.

## 권한

| 권한 | 쓰는 곳 | 빌드 |
|---|---|---|
| `pleos.car.permission.CAR_DRIVE`, `CAR_LIGHTS` | 기어, 브레이크, 핸들 각도, 깜빡이 | 제출 |
| `pleos.car.permission.CAR_DOORS`, `CAR_SEATS` | 문, 자리별 안전벨트 | 제출 |
| `pleos.car.permission.CAR_SAFETY`, `PRIVATE_CAR_SAFETY` | 경고등 | 제출 |
| `pleos.car.permission.TTS_SERVICE` | 일기 읽어 주기 | 제출 |
| `android.car.permission.CAR_SPEED`, `CAR_INFO` | 차 속도(실행 중에 물음), 연료 종류로 전기차 구분 | 제출 |
| `android.permission.FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED` | 상주 서비스, 시동 뒤 자동 시작 | 제출 |
| `android.permission.POST_NOTIFICATIONS` | 도착 일기 알림(실행 중에 물음) | 제출 |
| `pleos.car.permission.CAR_WINDOWS` | 와이퍼로 비 오는 창밖 | 검증 |
| `pleos.car.permission.STT_SERVICE`, `android.permission.RECORD_AUDIO` | 아기 이름 부르기 | 검증 |
| `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_ROUTE` | 내비 경유지에 휴게소를 넣어 보는 시험 | 검증 |
| `android.permission.INTERNET` | 외부 연결 확인. 제출 APK에는 SDK가 넣음 | 검증 |

- 제출 빌드는 13개를 선언합니다. APK에는 음성 SDK가 넣는 `INTERNET`, 차량 SDK가 넣는 `VEHICLE_BINDING`, AndroidX가 넣는 앱 전용 권한 하나가 더해져 16개가 듭니다. 제출 빌드 코드는 네트워크를 쓰지 않습니다.
- 검증 빌드는 7개를 더 선언해 APK에 22개가 듭니다. APK에 든 권한은 build-tools의 `aapt2 dump permissions <APK 파일>`로 봅니다.
- 제출 빌드는 와이퍼를 읽지 않아 비 오는 창밖이 없습니다. 안개와 눈은 경고등으로 정해서 두 빌드 모두 나옵니다.

## 단위 테스트

```powershell
.\gradlew.bat testDebugUnitTest      # 한 묶음만: --tests "com.example.pleosapp.engine.DriveEventEngineTest"
```

603개 모두 JVM에서 돌아서 에뮬레이터가 필요 없습니다. 결과는 `app\build\reports\tests\testDebugUnitTest\index.html`에 있고, 묶음별 내용은 [검증](verification.md)에 있습니다.

## 폴더 구조

```text
app/src/main/java/com/example/pleosapp/
  engine/    판단 규칙, 정령과 가족 상태, 기록 파일
  spike/     차량 신호를 받는 서비스, 속도, 음성, 부팅과 Gleo 수신기(시제품 때 이름이 남은 폴더)
  ui/        홈, 도착 카드, 가족, 강가, 기록 책, 회상, 엽서
app/src/main/assets/schema.json   차량 음성 비서 Gleo에 알리는 기능
app/src/debug/     검증 빌드 전용. 검증 콘솔, 시험 입력, 계측
app/src/release/   제출 빌드 전용. 시험 입력과 자세한 로그를 끄는 값
scripts/           에뮬레이터 켜기(start-emulator.ps1), 시연 트립(vc-trip-15s.js)
```

## 알려진 함정

빌드에서 자주 걸리는 곳입니다.

- 사용자 폴더 경로에 한글이 있으면 에뮬레이터와 AVD에서 오류가 난다고 알려져 있습니다. SDK와 AVD는 `C:\Android` 같은 영문 경로에 둡니다.
- 프로젝트 경로의 한글은 `gradle.properties`의 `android.overridePathCheck=true`로 넘깁니다. 그래도 막히면 프로젝트를 영문 경로로 옮깁니다.
- `org.gradle.jvmargs`에 `-Dfile.encoding=UTF-8`을 넣지 않습니다. 테스트 클래스패스가 깨집니다.

에뮬레이터에서 자주 걸리는 곳입니다.

- `adb reboot`은 이 에뮬레이터에서 멈춥니다. 끌 때는 `adb shell sync` 뒤에 전원을 끕니다. 설치 직후 강제로 끄면 APK가 사라질 수 있습니다.
- Virtual Console에 넣은 핸들 각도는 부호가 바뀌어 들어옵니다. 앱은 왼쪽을 음수로 보는 Android 규약을 따릅니다.
- PowerShell에서 `adb exec-out` 출력을 파일로 돌리면 그림이 깨집니다. `adb shell screencap -p /sdcard/shot.png` 뒤 `adb pull`로 받습니다.
