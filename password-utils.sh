#!/bin/bash

# 패스워드 유틸리티 스크립트
# 사용법: ./password-utils.sh [옵션] [사용자명] [패스워드]

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 도움말 함수
show_help() {
    echo -e "${BLUE}패스워드 유틸리티 스크립트${NC}"
    echo ""
    echo "사용법:"
    echo "  $0 verify <사용자명> <패스워드>    - 패스워드 검증"
    echo "  $0 hash <패스워드>                - 패스워드 해시 생성"
    echo "  $0 list                          - 모든 사용자 목록"
    echo "  $0 reset <사용자명> <새패스워드>   - 패스워드 리셋 (주의: 직접 DB 수정)"
    echo ""
    echo "예시:"
    echo "  $0 verify hyunchang88 mypassword"
    echo "  $0 hash newpassword"
    echo "  $0 list"
    echo "  $0 reset admin newadminpass"
}

# 데이터베이스 연결 정보 (환경에 맞게 수정)
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="vue_personal_project_db"
DB_USER="your_db_user"
DB_PASSWORD="your_db_password"

# 데이터베이스 연결 테스트
test_db_connection() {
    echo -e "${YELLOW}데이터베이스 연결 테스트 중...${NC}"
    
    # PostgreSQL 연결 테스트
    if command -v psql &> /dev/null; then
        PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "SELECT 1;" &> /dev/null
        if [ $? -eq 0 ]; then
            echo -e "${GREEN}✓ PostgreSQL 연결 성공${NC}"
            return 0
        fi
    fi
    
    # MySQL 연결 테스트
    if command -v mysql &> /dev/null; then
        mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASSWORD $DB_NAME -e "SELECT 1;" &> /dev/null
        if [ $? -eq 0 ]; then
            echo -e "${GREEN}✓ MySQL 연결 성공${NC}"
            return 0
        fi
    fi
    
    echo -e "${RED}✗ 데이터베이스 연결 실패${NC}"
    echo "연결 정보를 확인해주세요:"
    echo "  Host: $DB_HOST"
    echo "  Port: $DB_PORT"
    echo "  Database: $DB_NAME"
    echo "  User: $DB_USER"
    return 1
}

# 패스워드 검증 함수
verify_password() {
    local username="$1"
    local password="$2"
    
    if [ -z "$username" ] || [ -z "$password" ]; then
        echo -e "${RED}사용자명과 패스워드를 모두 입력해주세요.${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}사용자 '$username'의 패스워드를 검증 중...${NC}"
    
    # PostgreSQL에서 패스워드 해시 조회
    if command -v psql &> /dev/null; then
        local hash=$(PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT password FROM users WHERE username = '$username';" 2>/dev/null | xargs)
        
        if [ -z "$hash" ]; then
            echo -e "${RED}✗ 사용자 '$username'을 찾을 수 없습니다.${NC}"
            return 1
        fi
        
        # BCrypt 검증을 위한 Java 스크립트 생성
        cat > /tmp/verify_password.java << EOF
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class VerifyPassword {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java VerifyPassword <password> <hash>");
            System.exit(1);
        }
        
        String password = args[0];
        String hash = args[1];
        
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        boolean matches = encoder.matches(password, hash);
        
        if (matches) {
            System.out.println("PASSWORD_MATCH");
        } else {
            System.out.println("PASSWORD_NO_MATCH");
        }
    }
}
EOF
        
        # Java 컴파일 및 실행
        if command -v javac &> /dev/null && command -v java &> /dev/null; then
            javac -cp ".:lib/*" /tmp/verify_password.java 2>/dev/null
            if [ $? -eq 0 ]; then
                result=$(java -cp ".:lib/*:/tmp" VerifyPassword "$password" "$hash" 2>/dev/null)
                if [ "$result" = "PASSWORD_MATCH" ]; then
                    echo -e "${GREEN}✓ 패스워드가 일치합니다!${NC}"
                    rm -f /tmp/verify_password.java /tmp/VerifyPassword.class
                    return 0
                else
                    echo -e "${RED}✗ 패스워드가 일치하지 않습니다.${NC}"
                    rm -f /tmp/verify_password.java /tmp/VerifyPassword.class
                    return 1
                fi
            fi
        fi
        
        # Spring Boot 애플리케이션을 통한 검증 (대안)
        echo -e "${YELLOW}Java 환경이 없습니다. Spring Boot 애플리케이션을 통한 검증을 시도합니다...${NC}"
        
        # 간단한 패스워드 검증 (BCrypt 패턴 확인)
        if [[ $hash == \$2a\$* ]]; then
            echo -e "${YELLOW}BCrypt 해시 형식이 확인되었습니다.${NC}"
            echo -e "${BLUE}해시: $hash${NC}"
            echo -e "${YELLOW}정확한 검증을 위해서는 Spring Boot 애플리케이션을 실행하거나 Java 환경이 필요합니다.${NC}"
        else
            echo -e "${RED}✗ 유효하지 않은 해시 형식입니다.${NC}"
        fi
    else
        echo -e "${RED}✗ PostgreSQL 클라이언트가 설치되지 않았습니다.${NC}"
        return 1
    fi
}

# 패스워드 해시 생성 함수
generate_hash() {
    local password="$1"
    
    if [ -z "$password" ]; then
        echo -e "${RED}패스워드를 입력해주세요.${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}패스워드 해시 생성 중...${NC}"
    
    # Java를 통한 BCrypt 해시 생성
    cat > /tmp/generate_hash.java << EOF
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GenerateHash {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java GenerateHash <password>");
            System.exit(1);
        }
        
        String password = args[0];
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode(password);
        
        System.out.println("Password: " + password);
        System.out.println("Hash: " + hash);
    }
}
EOF
    
    if command -v javac &> /dev/null && command -v java &> /dev/null; then
        javac -cp ".:lib/*" /tmp/generate_hash.java 2>/dev/null
        if [ $? -eq 0 ]; then
            java -cp ".:lib/*:/tmp" GenerateHash "$password"
            rm -f /tmp/generate_hash.java /tmp/GenerateHash.class
        else
            echo -e "${RED}✗ Java 컴파일 실패. Spring Boot 의존성이 필요합니다.${NC}"
            rm -f /tmp/generate_hash.java
        fi
    else
        echo -e "${RED}✗ Java 환경이 설치되지 않았습니다.${NC}"
        rm -f /tmp/generate_hash.java
    fi
}

# 사용자 목록 조회 함수
list_users() {
    echo -e "${YELLOW}사용자 목록 조회 중...${NC}"
    
    if command -v psql &> /dev/null; then
        PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
        SELECT 
            id,
            username,
            email,
            role,
            created_at,
            updated_at
        FROM users 
        ORDER BY id;
        " 2>/dev/null
        
        if [ $? -eq 0 ]; then
            echo -e "${GREEN}✓ 사용자 목록 조회 완료${NC}"
        else
            echo -e "${RED}✗ 사용자 목록 조회 실패${NC}"
        fi
    else
        echo -e "${RED}✗ PostgreSQL 클라이언트가 설치되지 않았습니다.${NC}"
    fi
}

# 패스워드 리셋 함수 (주의: 직접 DB 수정)
reset_password() {
    local username="$1"
    local new_password="$2"
    
    if [ -z "$username" ] || [ -z "$new_password" ]; then
        echo -e "${RED}사용자명과 새 패스워드를 모두 입력해주세요.${NC}"
        return 1
    fi
    
    echo -e "${RED}⚠️  경고: 이 작업은 데이터베이스를 직접 수정합니다!${NC}"
    echo -e "${YELLOW}사용자 '$username'의 패스워드를 리셋하시겠습니까? (y/N)${NC}"
    read -r confirm
    
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo -e "${YELLOW}작업이 취소되었습니다.${NC}"
        return 0
    fi
    
    # 새 패스워드 해시 생성
    echo -e "${YELLOW}새 패스워드 해시 생성 중...${NC}"
    
    # Spring Boot 애플리케이션을 통한 해시 생성 (권장)
    if [ -f "target/todo-api-0.0.1-SNAPSHOT.jar" ]; then
        echo -e "${BLUE}Spring Boot 애플리케이션을 통한 패스워드 리셋을 권장합니다.${NC}"
        echo -e "${YELLOW}애플리케이션을 실행하고 관리자 API를 사용하세요.${NC}"
    else
        echo -e "${RED}✗ Spring Boot 애플리케이션이 빌드되지 않았습니다.${NC}"
        echo -e "${YELLOW}먼저 'mvn clean package'를 실행하세요.${NC}"
    fi
}

# 메인 로직
case "$1" in
    "verify")
        test_db_connection
        if [ $? -eq 0 ]; then
            verify_password "$2" "$3"
        fi
        ;;
    "hash")
        generate_hash "$2"
        ;;
    "list")
        test_db_connection
        if [ $? -eq 0 ]; then
            list_users
        fi
        ;;
    "reset")
        test_db_connection
        if [ $? -eq 0 ]; then
            reset_password "$2" "$3"
        fi
        ;;
    "help"|"-h"|"--help"|"")
        show_help
        ;;
    *)
        echo -e "${RED}알 수 없는 명령어: $1${NC}"
        show_help
        exit 1
        ;;
esac
