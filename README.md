# petpet

petpet 모드 중 홈 화면의 앱 아이콘을 누르면 앱이 실행되는 대신 손이 쓰다듬는 애니메이션이 나옵니다. (Android 전용)

## 기능
- **홈 탭**: petpet 시작 / done 종료, 접근성 서비스 상태, 총 쓰다듬은 횟수
- **통계 탭**: 쓰다듬은 앱 목록과 횟수 (기기 안 `SharedPreferences`에만 저장)
- **설정 탭**: "커피 한 잔 후원" 일회성 인앱 결제(= 배너 광고 제거), 후원 내역 복원, 개인정보처리방침 링크, 도움말
- 앱 메인 화면 하단 AdMob 배너 광고 (EEA 등은 UMP 동의 양식 후 요청, 광고 제거 구매자에게는 표시 안 함)
- 첫 실행 안내 팝업, 접근성 설정으로 이동하기 전 동의 팝업

## 동작 방식
- 접근성 서비스가 런처가 전면일 때만 투명 오버레이(`TYPE_ACCESSIBILITY_OVERLAY`)를 띄워 터치를 가로챔
- 앱 아이콘 탭 → 그 아이콘 위에만 손 움짤(`petpet_hand_*.png` 11프레임)이 재생, 아이콘이 아닌 곳은 무시 / 스와이프 → 제스처를 아래 창에 재생(페이지 넘기기·전체 앱 진입 허용)
- petpet 앱 자신의 아이콘은 탭을 통과시켜 앱으로 돌아올 수 있음
- 위젯도 위젯 전체 위에 손 움짤 재생 (런처가 위젯 정보를 노출하는 경우)
- 모드가 꺼지면(상단바/done) "petpet 모드가 꺼졌어요" 토스트 + 앱 화면 상태 즉시 갱신
- 상단바에 상태 알림(실행 중/꺼짐)이 항상 표시되고, 누르면 petpet 앱으로 돌아옴. 다른 앱/구글 피드가 앞에 오면 터치 가로채기만 잠시 내리고 모드는 유지

## 기술 스택
- Kotlin, Android View(코드로 구성한 UI, Compose 아님), AGP 8.10.1 / Kotlin 2.0.21
- compileSdk·targetSdk 36, **minSdk 26**, JDK 17
- Google Mobile Ads(AdMob) 23.6.0 (UMP 동의 포함), Play Billing 7.1.1

## 빌드 / 실행
1. Android Studio로 폴더를 열고 Gradle 동기화 후 실행 (저장소에 `gradlew` 래퍼는 포함되어 있지 않으므로 Android Studio 또는 설치된 Gradle 사용)
2. 앱 실행 → **petpet** 버튼 → 접근성 설정에서 "petpet" 서비스 켜기
   (스토어 외 경로로 설치했고 Android 13+ 라면 앱 정보 → ⋮ → "제한된 설정 허용" 후 켜야 함)
3. 다시 앱에서 **petpet** → 홈 화면으로 이동하며 모드 시작
4. 끝내려면 앱의 **done** 버튼 또는 알림의 done

### 광고 ID
`app/src/main/res/values/ads.xml`에는 **Google 공식 테스트 광고 ID**(`ca-app-pub-3940256099942544...`)가 들어 있어 그대로 빌드·실행됩니다. 출시할 때만 AdMob 콘솔에서 발급한 앱 ID/배너 단위 ID로 교체하세요. (개발 중 실제 광고 클릭 금지)

### 인앱 결제
상품 ID `coffee_remove_ads`(일회성)를 Play Console에 만들어 활성화해야 후원 버튼이 동작합니다. 상품이 없거나 Play 서비스에 연결할 수 없으면 후원 버튼은 "불러오는 중" 상태로 비활성이며 나머지 기능에는 영향이 없습니다. 구매 여부는 서버 검증 없이 기기 안에서 판단합니다.

### 릴리스 서명
`keystore.properties.example`을 `keystore.properties`로 복사해 값을 채우면 릴리스 빌드에 서명이 적용됩니다. 파일이 없으면 서명 없이 빌드됩니다. `keystore.properties`와 `*.jks`/`*.keystore`는 `.gitignore` 대상이라 커밋되지 않습니다.
```
./gradlew bundleRelease
```

## 프로젝트 구조
```
app/src/main/java/com/petpet/app/
  MainActivity.kt     탭 UI(홈/통계/설정), 광고·동의, 도움말/동의 팝업
  PetPetService.kt    접근성 서비스: 런처 감지, 오버레이 표시/제거, 상태 알림
  PetOverlayView.kt   터치 가로채기 + 손 애니메이션 프레임 재생
  PetStats.kt         쓰다듬기 통계 (SharedPreferences)
  Billing.kt          커피 후원 인앱 결제 / 광고 제거 상태
app/src/main/res/     손 프레임 이미지, 아이콘, ads.xml, 접근성 서비스 설정
docs/privacy-policy.html   개인정보처리방침 (GitHub Pages용)
store/                     Play 스토어 문구 초안·그래픽 (PLAY_STORE.md)
```

## 권한 / 개인정보
- **접근성 서비스** (`BIND_ACCESSIBILITY_SERVICE`, `canRetrieveWindowContent`, `canPerformGestures`): 런처 전면 여부 확인, 터치한 아이콘/위젯 영역 확인, 스와이프 제스처 전달에 사용. 읽은 정보는 기기 안에서만 쓰고 저장·전송하지 않음
- **알림** (`POST_NOTIFICATIONS`): petpet 모드 상태 표시
- 오버레이는 접근성 오버레이 방식이라 별도의 "다른 앱 위에 표시" 권한은 쓰지 않음
- 광고(AdMob)와 결제(Google Play)는 Google이 처리. `allowBackup=false`
- 개인정보처리방침: [`docs/privacy-policy.html`](docs/privacy-policy.html) (앱 도움말/설정의 링크는 `https://10000sun.github.io/petpetapp/privacy-policy.html`, GitHub Pages 설정 필요)

## 출시
Play 스토어 출시 체크리스트·스토어 문구·데이터 보안/접근성 선언 초안은 `store/PLAY_STORE.md` 참고.

## 상태 / 한계
- 개인정보처리방침의 문의 이메일이 `[문의 이메일을 입력하세요]`로 비어 있음 — 출시 전 직접 채워야 함
- 위젯 쓰다듬기는 런처가 위젯 정보를 접근성 트리에 노출하는 경우에만 동작
- 자동화 테스트 없음, CI 없음
