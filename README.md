# screen-translator

screen translator는 일본어 UI를 보면서도 한국어로 바로 이해하고 싶다는 생각에서 시작한 Android용 오버레이 앱입니다.
현재 보고 있는 일본어 화면 위에 한국어 해석을 겹쳐 보여줍니다.

- 현재 보고 있는 앱에서 일본어 텍스트를 한국어로 바로 확인할 수 있습니다. 화면을 캡처하거나 별도의 전환 없이 바로 사용할 수 있습니다.
- **접근성 트리**에서 문자열과 좌표를 가져와, 해당 위치에 한국어 해석을 오버레이로 보여줍니다.

## 화면
오버레이 적용 before & after 화면입니다. 


| 원본 | 오버레이 적용 |
|---|---|
| ![원본 일본어 화면](docs/images/before.png) | ![한국어 오버레이가 적용된 화면](docs/images/after.png) |

카드가 격자로 늘어선 화면에서도 각 항목의 라벨 위치를 그대로 따라갑니다.

| 스타일 검색 화면 | 앱 실행 화면 |
|---|---|
| ![스타일 검색 화면 오버레이](docs/images/styles.png) | ![앱의 접근성 권한 안내 화면](docs/images/setup.png) |

> 스크린샷의 대상 앱은 예시로 사용한 서드파티 일본어 앱입니다.

## 기술 스택

- Kotlin
- Android AccessibilityService
- Android WindowManager / OverlayWindow
- Canvas 기반 오버레이 렌더링
- ML Kit Translate (`ja -> ko`)
- Gradle, AGP, Android SDK

## 사용 방법

1. 앱을 설치한 뒤, 권한 안내에 따라 접근성 권한을 허용합니다.
2. 대상 앱에서 일본어 화면을 확인합니다.
3. 앱이 현재 화면의 텍스트를 인식하고, 한국어 해석을 해당 위치에 오버레이로 표시합니다.



## 빌드

```bash
./gradlew installDebug          # 빌드 + 설치. adb install 따로 안 해도 됨
./tools/dev.sh enable           # 접근성 재허용 (재설치하면 항상 꺼짐)
./tools/dev.sh log              # adb logcat -s ScrTrans
./tools/dev.sh shot out.png     # 디스플레이 ID를 명시한 screencap
```


이 기기에서 검증된 툴체인:

| | |
|---|---|
| JDK | 21.0.5 (`brew install openjdk@21`, keg-only. `gradle.properties`의 `org.gradle.java.home`에 박아서 전역 `java`를 안 건드림) |
| SDK | `/opt/homebrew/share/android-commandlinetools`, `platforms;android-36`, `build-tools;36.0.0` |
| AGP / Kotlin / Gradle | 8.13.2 / 2.3.21 / 8.14.5 |
| compile/target/min SDK | 36 / 36 / 30 |
| 의존성 | `com.google.mlkit:translate:17.0.3` — 이것 하나뿐 |

코드에는 AndroidX·Material·Compose가 없고 XML 레이아웃도 없습니다. 
`MainActivity`는 순수 `android.app.Activity`에 코드로 뷰를 조립합니다.

ML Kit은 ja/ko (번역)모델을 받기 위해 **최초 1회** 네트워크가 필요합니다. 
(용어집 항목은 그와 무관하게 오프라인에서 동작)


