# changojigi-fabric-mod

`chest-discord-bot` 단일-섬 인스턴스와 연동해 현재 설정을 동기화하고, 상자/섬 은행 로그를 전송하는 Fabric 클라이언트 모드입니다.

## 요구 사항

- Minecraft `1.21.4`
- Fabric Loader `0.16.10+`
- Java `21`
- 동작 중인 `chest-discord-bot` 서버

## 주요 기능

- 서버 연결만으로 현재 섬 설정 동기화
- 상자 열기/닫기 차이를 계산해 창고 로그 전송
- 서버 인스턴스 기준 관리자 모드 진입 및 상자 등록
- 섬 은행 입금/출금 메시지 감지

## 설정 파일

경로: `<게임 디렉터리>/config/chestbot.json`

```json
{
  "server_url": "https://chestbot.kro.kr"
}
```

## 인게임 명령어

### 일반 사용자

- `/chestbot server <주소>` / `/창고봇 서버 <주소>`
- `/chestbot connect` / `/창고봇 연결`
- `/chestbot list` / `/창고봇 목록`
- `/chestbot reload` / `/창고봇 새로고침`
- `/chestbot depositreason <사유>` / `/창고봇 입금사유 <사유>`

### 관리자

- `/chestbot admin` / `/창고봇 관리자`
- `/chestbot add <창고이름>` / `/창고봇 추가 <창고이름>`
- `/chestbot remove <창고이름>` / `/창고봇 제거 <창고이름>`

## 사용 흐름

1. Discord에서 `/창고 서버`로 서버 주소를 확인합니다.
2. 게임에서 `/창고봇 서버 <서버주소>`를 입력합니다.
3. `/창고봇 연결`을 실행해 현재 설정을 불러옵니다.
4. 창고 등록이 필요하면 Discord에서 `/창고 관리자코드`를 발급받고, 게임에서 `/창고봇 관리자 <코드>` 후 `/창고봇 추가 <이름>`을 사용합니다.
5. 이후 상자/은행 로그가 자동 전송됩니다.

## 백엔드 연동 경로

- `POST /api/v1/client/connect`
- `POST /api/v1/client/admin/connect`
- `POST /api/v1/client/admin/finalize`
- `POST /api/v1/client/events/chest-log`
- `POST /api/v1/client/events/island-bank-log`

## 빌드

```powershell
.\gradlew.bat :mc1_21_4:build
```

- 1.21.4 JAR 출력 위치: `mc1_21_4/build/libs/`
- 루트 `build`는 다중 버전 서브프로젝트를 함께 빌드할 때 사용합니다.

## 라이선스

이 프로젝트는 **GPL-3.0-only** 라이선스를 따릅니다. 자세한 내용은 `LICENSE` 파일을 확인하세요.
