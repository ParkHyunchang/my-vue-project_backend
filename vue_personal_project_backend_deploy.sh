#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────────
# 백엔드 수동 배포 스크립트 (레지스트리 pull 방식)
# GitHub Actions가 자동 배포하지만, 수동으로 재배포가 필요할 때 사용
#
# 사용법:
#   GHCR_PAT=<토큰> GITHUB_ACTOR=<깃허브아이디> ./vue_personal_project_backend_deploy.sh
# ──────────────────────────────────────────────────────────────────────────────

set -e

DEPLOY_DIR="${DEPLOY_DIR:-/volume1/docker/my-vue-project_backend}"

echo "🚀 백엔드 배포 시작..."

# GHCR 로그인
if [ -n "$GHCR_PAT" ] && [ -n "$GITHUB_ACTOR" ]; then
    echo "🔐 GHCR 로그인 중..."
    echo "$GHCR_PAT" | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
else
    echo "⚠️  GHCR_PAT 또는 GITHUB_ACTOR 환경변수가 설정되지 않았습니다."
    echo "   이미 로그인되어 있다면 계속 진행합니다..."
fi

echo "📁 배포 디렉토리로 이동: $DEPLOY_DIR"
cd "$DEPLOY_DIR"

if [ ! -f "docker-compose.yml" ]; then
    echo "❌ docker-compose.yml을 찾을 수 없습니다!"
    echo "   $DEPLOY_DIR 에 docker-compose.yml이 있는지 확인해주세요."
    exit 1
fi

if [ ! -f ".env" ]; then
    echo "❌ .env 파일을 찾을 수 없습니다!"
    echo "   DB 환경변수가 포함된 .env 파일을 생성해주세요."
    exit 1
fi

echo "📦 최신 백엔드 이미지 pull 중..."
docker-compose pull backend

echo "🔄 백엔드 컨테이너 재시작 중..."
docker-compose up -d --no-deps backend

docker image prune -f

echo ""
echo "🎉 백엔드 배포가 완료되었습니다!"
echo ""
echo "📊 컨테이너 상태:"
docker-compose ps

echo ""
echo "📋 로그 확인: docker-compose logs -f backend"
