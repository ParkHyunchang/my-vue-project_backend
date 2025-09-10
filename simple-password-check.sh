#!/bin/bash

# 간단한 패스워드 검증 스크립트
# Spring Boot 애플리케이션을 통한 패스워드 검증

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== 패스워드 검증 도구 ===${NC}"
echo ""

# 사용자 입력 받기
read -p "사용자명을 입력하세요: " username
read -s -p "패스워드를 입력하세요: " password
echo ""

if [ -z "$username" ] || [ -z "$password" ]; then
    echo -e "${RED}사용자명과 패스워드를 모두 입력해주세요.${NC}"
    exit 1
fi

echo -e "${YELLOW}패스워드 검증 중...${NC}"

# Spring Boot 애플리케이션이 실행 중인지 확인
if curl -s http://localhost:3200/api/auth/check > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Spring Boot 애플리케이션이 실행 중입니다.${NC}"
    
    # 로그인 API 호출
    response=$(curl -s -X POST http://localhost:3200/api/auth/login \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$username\",\"password\":\"$password\"}")
    
    # 응답에서 success 필드 확인
    if echo "$response" | grep -q '"success":true'; then
        echo -e "${GREEN}✓ 패스워드가 일치합니다!${NC}"
        
        # 사용자 정보 추출
        role=$(echo "$response" | grep -o '"role":"[^"]*"' | cut -d'"' -f4)
        email=$(echo "$response" | grep -o '"email":"[^"]*"' | cut -d'"' -f4)
        
        echo -e "${BLUE}사용자 정보:${NC}"
        echo "  사용자명: $username"
        echo "  이메일: $email"
        echo "  권한: $role"
    else
        echo -e "${RED}✗ 패스워드가 일치하지 않거나 사용자가 존재하지 않습니다.${NC}"
        echo "응답: $response"
    fi
else
    echo -e "${RED}✗ Spring Boot 애플리케이션이 실행되지 않았습니다.${NC}"
    echo -e "${YELLOW}애플리케이션을 먼저 실행해주세요:${NC}"
    echo "  mvn spring-boot:run"
    echo ""
    echo -e "${YELLOW}또는 데이터베이스에서 직접 확인하려면:${NC}"
    echo "  psql -h localhost -U your_user -d vue_personal_project_db"
    echo "  SELECT username, password FROM users WHERE username = '$username';"
fi
