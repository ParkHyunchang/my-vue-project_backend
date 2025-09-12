#!/bin/bash

# 백엔드 애플리케이션 재시작 스크립트
# 이 스크립트는 Docker Compose를 사용하여 백엔드 애플리케이션을 재시작합니다.

echo "백엔드 애플리케이션 재시작을 시작합니다..."

# 현재 디렉토리 확인
echo "현재 디렉토리: $(pwd)"

# Docker Compose 파일이 존재하는지 확인
if [ ! -f "docker-compose.yml" ]; then
    echo "docker-compose.yml 파일을 찾을 수 없습니다."
    exit 1
fi

# 백엔드 컨테이너만 재시작
echo "백엔드 컨테이너를 재시작합니다..."
docker-compose restart backend

# 재시작 상태 확인
echo "컨테이너 상태 확인 중..."
sleep 5
docker-compose ps

# 로그 확인 (최근 20줄)
echo ""
echo "백엔드 컨테이너 로그 (최근 20줄):"
docker-compose logs --tail=20 backend

echo ""
echo "백엔드 애플리케이션 재시작이 완료되었습니다!"
echo "애플리케이션이 정상적으로 시작되었는지 확인해주세요."
