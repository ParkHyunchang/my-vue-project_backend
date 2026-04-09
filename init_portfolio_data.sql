-- ============================================================
-- Career 데이터 (경력 및 프로젝트)
-- ============================================================
INSERT INTO vue_personal_project_db.career (icon, company, period, badge, role_desc, projects, tags, sort_order, created_at, updated_at)
VALUES ('AI', '(주)포니링크', '2025.04.21 ~ 재직중', '재직중', 'LLM 개발운영 — 대규모 언어 모델 프로젝트', '["SKT — LLM 서비스 개발 및 운영","H사 — 대규모 언어 모델 개발"]', '["Python","LLM","Spring Boot","Docker"]', 1, NOW(), NOW());

INSERT INTO vue_personal_project_db.career (icon, company, period, badge, role_desc, projects, tags, sort_order, created_at, updated_at)
VALUES ('NLP', '(주)NHN다이퀘스트', '2021.11.29 ~ 2024.10.18 (35개월)', NULL, 'AI 콜봇·챗봇 개발 및 유지보수 / 딥러닝 CI/CD 구축', '["국민은행 — 챗봇/콜봇 SI 1차","법률구조공단 — 챗봇 백엔드 개발","국민은행 — 챗봇/콜봇 SI 고도화","한전, 신한은행, 흥국화재, 하나손보, 신한라이프, 신한투자증권, 삼성카드 등 SM"]', '["Java","Spring","AI/NLP","Docker","Jenkins"]', 2, NOW(), NOW());

INSERT INTO vue_personal_project_db.career (icon, company, period, badge, role_desc, projects, tags, sort_order, created_at, updated_at)
VALUES ('NAV', '(주)아이나비모빌리티', '2021.10.05 ~ 2021.11.09 (1개월)', NULL, '서버 개발', '["네비게이션 로그 개발"]', '["Java","서버 개발"]', 3, NOW(), NOW());

INSERT INTO vue_personal_project_db.career (icon, company, period, badge, role_desc, projects, tags, sort_order, created_at, updated_at)
VALUES ('SEC', '(주)나일소프트', '2017.09.04 ~ 2021.09.24 (49개월)', NULL, '웹 취약점 점검 솔루션 개발 및 유지보수 / Java 웹 개발', '["교통안전공단, 아산병원 — 웹 취약점 점검 프로젝트 SI","국민카드 — 웹 취약점 점검 프로젝트 SM","KB국민은행 등 다수 기업 SM"]', '["Java","JSP","Oracle DB","보안 솔루션"]', 4, NOW(), NOW());

INSERT INTO vue_personal_project_db.career (icon, company, period, badge, role_desc, projects, tags, sort_order, created_at, updated_at)
VALUES ('EMB', '칼라세븐', '2016.11.02 ~ 2017.02.01 (3개월)', NULL, '임베디드 개발', '["생리통 치료기기 임베디드 개발"]', '["Embedded","C/C++"]', 5, NOW(), NOW());


-- ============================================================
-- Experience 데이터 (교육 및 경험)
-- ============================================================
INSERT INTO vue_personal_project_db.experience (title, subtitle, description, period, sort_order, created_at, updated_at)
VALUES ('IT 융복합기기 회로설계 전문가과정', '대한상공회의소', 'PCB 설계, 회로 설계 및 프로토타입 제작 등을 하며 실무 경험을 쌓았습니다.', '2016.03.02 ~ 2016.06.01', 1, NOW(), NOW());

INSERT INTO vue_personal_project_db.experience (title, subtitle, description, period, sort_order, created_at, updated_at)
VALUES ('멀티프레임워크기반 웹 전문 개발자', '에이콘아카데미', '프론트엔드와 백엔드 전반에 걸친 웹 개발 기술을 체계적으로 학습하고 실무 프로젝트를 통해 실전 경험을 쌓았습니다.', '2017.03.09 ~ 2017.09.12', 2, NOW(), NOW());

INSERT INTO vue_personal_project_db.experience (title, subtitle, description, period, sort_order, created_at, updated_at)
VALUES ('회사 웹 개발자', '다수 기업', '여러 회사의 솔루션 및 개발 프로젝트를 수행하며 폭넓은 기술 경험과 프로젝트 관리 능력을 키워가고 있습니다.', '2017.09 ~ 현재', 3, NOW(), NOW());


-- ============================================================
-- PortfolioSkill 데이터 (포트폴리오 스킬 카드)
-- ============================================================
INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p1', 'AI / LLM', '["Claude / OpenAI API 연동 및 활용","프롬프트 엔지니어링 설계","Anthropic Agent SDK 기반 개발","RAG 파이프라인 구성","AI 기능 서비스 통합 개발"]', 1, NOW(), NOW());

INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p2', 'Java', '["OOP 설계 (상속 / 다형성 / 캡슐화)","JSP / Servlet 웹 애플리케이션","JDBC 데이터베이스 연동","Tomcat 서버 배포","Maven / Gradle 빌드 관리"]', 2, NOW(), NOW());

INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p3', 'Spring / SpringBoot', '["MVC 패턴 기반 웹 개발","REST API 설계 및 구현","Spring Security 인증 / 인가","JPA / Hibernate ORM","MyBatis 연동"]', 3, NOW(), NOW());

INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p4', 'Vue 3', '["Composition API & script setup","Pinia 전역 상태 관리","Vue Router SPA 구성","Vite 빌드 환경 설정","컴포넌트 기반 UI 설계"]', 4, NOW(), NOW());

INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p5', 'Database', '["PostgreSQL / Oracle / MySQL","ERD 설계 및 정규화","SQL 쿼리 최적화","인덱스 / 트랜잭션 관리","저장 프로시저 & 뷰 활용"]', 5, NOW(), NOW());

INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p6', 'Linux Server', '["Ubuntu / CentOS 서버 환경","SSH 원격 접속 & 파일 관리","Nginx / Apache 웹서버 설정","Shell Script 배포 자동화","방화벽(firewalld) & 포트 관리"]', 6, NOW(), NOW());

INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p7', 'HTML / CSS', '["반응형 웹 레이아웃 (Flexbox / Grid)","CSS 애니메이션 & 트랜지션","Sass(SCSS) 전처리기","크로스 브라우저 호환성 대응","BEM 방법론 기반 CSS 설계"]', 7, NOW(), NOW());

INSERT INTO vue_personal_project_db.portfolio_skill (css_class, title, descriptions, sort_order, created_at, updated_at)
VALUES ('p8', 'JavaScript', '["ES6+ 문법 (Arrow / Destructuring 등)","DOM 조작 & 이벤트 핸들링","jQuery 플러그인 활용","JSON / AJAX 비동기 통신","외부 REST API 연동"]', 8, NOW(), NOW());
