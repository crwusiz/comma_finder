# 삼성 실기기 Chrome 연결 테스트 — v1.76

2026-11-07, 무선 디버깅으로 연결된 Galaxy S24 Ultra(`SM-S928N`, Android 17 / API 37, One UI 9)에서 검사했습니다. 활성 Chrome 버전은 `156.0.8078.80`, target SDK는 36입니다. 기존 앱과 동일한 인증서로 서명한 Comma Finder v1.76 릴리스 APK를 `adb install -r`로 설치했습니다.

## 확인한 원인과 수정

처음 Chrome에서 `http://192.168.0.82:7000/`을 열면 `ERR_TOO_MANY_RETRIES`가 표시됐습니다. 당시 `ACCESS_LOCAL_NETWORK`는 `granted=true`였지만 `REVOKED_COMPAT` 플래그가 있었고, AppOps는 아래 상태였습니다.

```text
Uid mode: ACCESS_LOCAL_NETWORK: ignore
ACCESS_LOCAL_NETWORK: allow
```

패키지의 허용 표시보다 UID 차단이 우선하므로 단순 권한 조회만으로 접속 가능 여부를 판단할 수 없었습니다. 설정에서 Chrome의 **권한 → 근처 기기 → 허용**을 적용하고 새 요청을 보내자 실제 대시보드가 열렸습니다.

`ExternalBrowserLauncher`는 Android 17 이상에서 선택한 브라우저의 권한과 UID AppOps를 함께 확인하도록 수정했습니다. `checkOpRawNoThrow`로 명시적인 차단(`MODE_IGNORED`, `MODE_ERRORED`)을 감지해 기존 설정 안내로 연결합니다. 실행 전 백그라운드 상태 때문에 전경에서만 가능한 접근을 거부로 오판하지 않도록 raw 모드를 조회합니다. 조회가 불가능하면 기존 권한 조회 결과를 사용합니다. API 동작은 [Android AppOpsManager 공식 문서](https://developer.android.com/reference/android/app/AppOpsManager#checkOpRawNoThrow(java.lang.String,%20int,%20java.lang.String,%20java.lang.String))를 참고했습니다.

## 실기기 검사 결과

| 검사 | 결과 |
| --- | --- |
| 현재 Wi-Fi에서 포트 7000 전체 검색 | `192.168.0.76`, `192.168.0.82` 두 IP 표시, 자동 접속 없음 |
| 목록에서 `192.168.0.82` 선택 | 앱 내부 Openpilot Dashboard 표시 |
| 내부 화면 우측 외부 브라우저 버튼 → Chrome | 선택한 `192.168.0.82:7000` 대시보드 표시 |
| 시스템 설정에서 Chrome 근처 기기 거부 | `granted=true + REVOKED_COMPAT`, UID AppOps `ignore` 상태 재현 |
| 위 상태에서 앱의 외부 브라우저 → Chrome 선택 | Chrome을 열지 않고 Chrome 권한 설정 안내 표시. 실제 앱 UID에서 차단 감지 확인 |
| 설정 열기 → 거부 상태로 앱 복귀 | 내부 화면 유지, 설정/브라우저 반복 실행 없음 |
| 다시 설정 열기 → Chrome 근처 기기 허용 → 앱 복귀 | Chrome이 같은 URL을 자동으로 열고 대시보드 표시 |
| 최종 권한 상태 | Chrome 근처 기기 허용, `REVOKED_COMPAT` 없음, UID 차단 없음 |
| 단위 테스트 / lint / debug·release 빌드 | 28개 통과 / 오류 0건·기존 경고 7건 / 성공 |

Chrome 권한을 거부하고 복귀한 직후 기존 내부 WebView에 `ERR_CONNECTION_ABORTED`가 한 번 표시됐고, 다시 시도하면 정상적으로 열렸습니다. 이때 별도 HTTP 확인은 200이었습니다. 실제 대시보드의 표시 및 브라우저 전환을 검사했으며, 차량 설정 변경·재부팅 등의 제어 기능은 실행하지 않았습니다.

최종 릴리스 APK는 `build/outputs/apk/release/comma_finder_v1.76.apk`입니다. 서명 인증서 SHA-256은 기존 설치본과 같은 `433ca8e81e7f64ae35914a2a32552fbe10e668efb6c34ea8a14cdeb7641a99fa`입니다. 기존 앱 데이터는 유지했고, Chrome 데이터 삭제나 보안 플래그·VPN 변경은 하지 않았습니다. 검사 종료 시 Chrome은 실제 대시보드를 표시했습니다.

## 증거

상세 증거는 `build/validation-samsung/`에 저장했습니다. 빌드 정리 시 삭제될 수 있습니다.

- 최초 Chrome 오류: [화면](build/validation-samsung/chrome-before.png), [화면 구조](build/validation-samsung/chrome-before.xml)
- 최종 APK에서 두 IP 검색: [화면 구조](build/validation-samsung/app-scan-final.xml)
- 앱 내부 화면: [화면 구조](build/validation-samsung/app-internal-final.xml)
- 우측 버튼 → Chrome 접속: [화면](build/validation-samsung/app-to-chrome-success.png), [화면 구조](build/validation-samsung/app-to-chrome-success.xml)
- 호환성 권한 차단 재현: [권한](build/validation-samsung/chrome-compat-denied-state.txt), [AppOps](build/validation-samsung/chrome-compat-denied-appops.txt)
- 앱에서 차단 감지: [화면](build/validation-samsung/browser-compat-denied-guide.png), [화면 구조](build/validation-samsung/browser-compat-denied-guide.xml)
- 거부 상태로 복귀: [화면 구조](build/validation-samsung/browser-denied-return.xml)
- 허용 후 자동 접속: [화면](build/validation-samsung/browser-grant-return-success.png), [화면 구조](build/validation-samsung/browser-grant-return-success.xml)
- 최종 Chrome 권한: [상태](build/validation-samsung/chrome-final-state.txt)
