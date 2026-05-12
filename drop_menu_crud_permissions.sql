-- ============================================================
-- menu_crud_permissions 테이블 제거 스크립트
-- ------------------------------------------------------------
-- 사유: 권한 체계를 "메뉴 접근 = 전체 CRUD" 단일 모델로 단순화.
--       역할 × 메뉴 가시성은 menu_permissions 테이블 한 곳에서만
--       관리한다. menu_crud_permissions 테이블은 더 이상 사용되지
--       않으며, 백엔드 코드(엔티티/리포지토리/서비스)도 제거되었음.
--
-- 실행 시점: 새 백엔드 빌드를 배포한 직후 한 번만 실행.
--
-- 실행:
--   mysql -u <user> -p vue_personal_project_db < drop_menu_crud_permissions.sql
-- ============================================================

DROP TABLE IF EXISTS vue_personal_project_db.menu_crud_permissions;

-- 결과 확인
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'vue_personal_project_db'
  AND table_name = 'menu_crud_permissions';
-- 위 SELECT가 0 rows를 반환하면 정상 제거된 것임.
