# 외부 브라우저 연결 테스트 — v1.75

이후 One UI 9 실기기에서 재현한 Chrome 권한 차단 원인과 v1.76 수정 검증은 [삼성 실기기 테스트 기록](SAMSUNG_CHROME_TEST.md)에 정리했습니다.

2026-11-07, 사용자가 실행한 `Pixel_10a` 가상기기(`emulator-5554`, Android 17 / API 37)에서 검사했습니다. Chrome은 `145.0.7632.218`, target SDK 36이며 시작 시 `ACCESS_LOCAL_NETWORK`가 이미 허용되어 있었습니다. Comma Finder v1.75 디버그 APK를 설치해 검사했습니다.

| 검사 | 결과 |
| --- | --- |
| PC에서 실제 `http://192.168.0.82:7000/` 조회 | HTTP 200 |
| 가상기기 Chrome에서 실제 주소 직접 열기 | Openpilot Dashboard 표시. 기존 `ERR_TOO_MANY_RETRIES`는 재현되지 않음 |
| 앱 검색 → `10.0.2.2:7000` 선택 → 내부 화면 | 정적 테스트 페이지와 `JavaScript READY` 표시 |
| 앱 우측 외부 브라우저 버튼 → Chrome | 전달한 주소와 테스트 페이지 표시 |
| 기본 Chrome과 추가 테스트 브라우저가 함께 설치된 경우 | 두 브라우저 모두 선택 목록에 표시 |
| 로컬 네트워크 권한이 없는 API 37 테스트 브라우저 선택 | 해당 브라우저 이름이 포함된 권한 안내 표시. 브라우저를 실행하지 않음 |
| 설정 열기 → 권한을 거부한 채 복귀 | 내부 화면에 머무름. 설정/브라우저 반복 실행 없음 |
| 설정 → Nearby devices → Allow → 앱 복귀 | 선택한 브라우저가 원래 URL을 자동으로 열고 HTML 및 JavaScript 표시 |
| 내부 화면 닫기 → IP 목록 길게 누르기 → Chrome | 목록 유지, 브라우저 선택 및 같은 URL 접속 정상 |
| 단위 테스트 / lint / APK 빌드 | 28개 통과 / 오류 0건·기존 경고 7건 / 성공 |

가상기기는 NAT 네트워크 `10.0.2.0/24`를 사용합니다. 앱 검색 및 브라우저 전환 검사는 호스트의 임시 `127.0.0.1:7000` 정적 서버를 가상기기의 `10.0.2.2:7000`에서 접속해 수행했습니다. 실제 `192.168.0.82:7000`의 Chrome 직접 접속 검사와 구분해야 합니다. 서버 설정 변경이나 대시보드의 기기 제어 버튼은 실행하지 않았습니다.

초기에는 실제 서버의 GET/HEAD만 전달하는 중계를 사용해 대시보드의 초기 문서를 확인했습니다. 해당 중계는 WebSocket을 차단하므로 NiceGUI 대시보드의 지속 연결 및 제어 기능을 검증하는 데 적합하지 않았고, 이후 브라우저 전환/권한 검사는 외부 리소스와 WebSocket이 없는 정적 페이지로 진행했습니다. 실제 기기의 One UI 9 / Chrome 오류는 이 가상기기에서 재현하지 못했습니다.

검사 중 기본 브라우저만 조회되는 경우를 확인해 브라우저 조회에 `MATCH_ALL`을 적용했습니다. 브라우저를 선택한 뒤 해당 UID의 권한을 확인하며, Comma Finder가 다른 앱의 권한을 대신 부여하지 않습니다. 인증서 검증, Chrome 보안 플래그, VPN 및 호스트 네트워크 설정은 변경하지 않았습니다.

임시 테스트 브라우저 `com.commafinder.browserfixture`는 검사 후 제거했고, 임시 중계/정적 서버도 종료했습니다. Chrome의 로컬 네트워크 권한은 처음과 같이 허용 상태입니다. 사용자 가상기기는 종료하지 않았으며, 마지막에는 Chrome에서 실제 대시보드를 표시했습니다.

상세 증거는 `build/validation-api37/`에 저장했습니다. 이 디렉터리는 빌드 정리 시 삭제될 수 있습니다.

- 실제 서버 Chrome 접속: [최종 화면](build/validation-api37/chrome-real-device-final.png), [화면 구조](build/validation-api37/chrome-real-device-final.xml)
- 복수 브라우저 선택: [화면 구조](build/validation-api37/v175-multiple-browsers.xml)
- 권한 거부 안내: [화면](build/validation-api37/browser-permission-denied.png)
- 거부 상태로 설정 복귀: [화면 구조](build/validation-api37/v175-settings-denied-return.xml)
- 설정 허용 후 자동 접속: [화면](build/validation-api37/browser-settings-grant-success.png), [화면 구조](build/validation-api37/v175-browser-grant-content.xml)
- 목록 길게 누르기 → Chrome: [화면](build/validation-api37/v175-chrome-static-success.png), [화면 구조](build/validation-api37/v175-longpress-chrome-success.xml)

동일 검사에 사용하는 임시 정적 서버 및 테스트 브라우저 소스도 `build/validation-api37/`에 보관되어 있습니다. 운영 APK에는 포함되지 않습니다.
