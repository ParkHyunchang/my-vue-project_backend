# 관리자 권한 설정 가이드

## 개요
이 프로젝트에서는 특정 사용자만 관리자 권한을 가질 수 있도록 제한하고 있습니다.

## 설정 방법

### 1. 로컬 개발 환경
- `src/main/resources/application.properties` 파일에서 `admin.users` 설정을 수정하세요.
- 기본값: `admin.users=hyunchang88,admin`

### 2. NAS 배포 환경
- `src/main/resources/application-nas.properties` 파일에서 `admin.users` 설정을 수정하세요.
- 예시: `admin.users=hyunchang88,admin,superuser`

## 설정 형식
```
admin.users=사용자명1,사용자명2,사용자명3
```

## 주의사항
1. **회원가입 시**: 일반 사용자는 관리자 권한을 선택할 수 없습니다.
2. **관리자 생성**: 오직 설정된 사용자명으로만 관리자 권한을 가질 수 있습니다.
3. **권한 수정**: 기존 사용자를 관리자로 변경하려면 해당 사용자명이 설정 목록에 있어야 합니다.

## 보안 특징
- 백엔드와 프론트엔드 모두에서 이중 검증
- 설정 파일 기반으로 동적 관리
- 실시간 유효성 검사

## 배포 시 체크리스트
- [ ] `application-nas.properties`에서 `admin.users` 설정 확인
- [ ] 허용된 관리자 사용자명이 정확한지 확인
- [ ] 배포 후 관리자 계정으로 로그인 테스트
- [ ] 일반 사용자 회원가입 시 관리자 권한 선택 불가 확인
