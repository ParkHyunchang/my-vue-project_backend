#!/bin/bash

set -e

# Windows 줄바꿈(CRLF)을 Unix 줄바꿈(LF)으로 자동 변환
if command -v dos2unix >/dev/null 2>&1; then
    dos2unix "$0" 2>/dev/null || true
elif command -v sed >/dev/null 2>&1; then
    sed -i 's/\r$//' "$0" 2>/dev/null || true
fi

echo "🚀 백엔드 배포를 시작합니다..."

# 1단계: tar 파일 확인
echo "📦 1단계: 배포 패키지 확인"
TAR_FILE=$(ls backend_deployment_*.tar.gz 2>/dev/null | head -1)

if [ -z "$TAR_FILE" ]; then
    echo "❌ 배포 패키지를 찾을 수 없습니다!"
    echo "   backend_deployment_*.tar.gz 파일을 이 디렉토리에 업로드해주세요."
    exit 1
fi

echo "✅ 배포 패키지 발견: $TAR_FILE"

# 2단계: 기존 컨테이너 중지 및 삭제
echo "🛑 2단계: 기존 백엔드 컨테이너 중지 및 삭제"
docker stop vue_personal_project-backend 2>/dev/null || true
docker rm vue_personal_project-backend 2>/dev/null || true

# 3단계: 기존 이미지 삭제
echo "🗑️ 3단계: 기존 백엔드 이미지 삭제"
docker rmi my-vue-project-backend_backend:latest 2>/dev/null || true

# 4단계: 기존 배포 파일 정리
echo "🧹 4단계: 기존 배포 파일 정리"
rm -f app.jar docker-compose.yml Dockerfile init.sql

# 5단계: 새로운 배포 패키지 압축 해제
echo "📂 5단계: 배포 패키지 압축 해제"
tar -xzf "$TAR_FILE"

# 5-1단계: 배포 스크립트 권한 설정
echo "🔧 5-1단계: 배포 스크립트 권한 설정"
chmod 777 vue_personal_project_backend_deploy.sh
sed -i 's/\r$//' vue_personal_project_backend_deploy.sh

# 6단계: Docker 이미지 빌드
echo "🔨 6단계: Docker 이미지 빌드"
docker-compose build --no-cache

# 7단계: 컨테이너 실행
echo "▶️ 7단계: 컨테이너 실행"
docker-compose up -d

echo ""
echo "🎉 백엔드 배포가 완료되었습니다!"
echo "📊 컨테이너 상태:"
docker ps | grep vue_personal_project-backend

echo ""
echo "🌐 API 주소 예시: http://your-nas-ip:3200/my-vue-project/todos"
echo ""
echo "📋 로그 확인: docker logs -f vue_personal_project-backend"