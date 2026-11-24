# TEAM-11 오프라인 전략 연구

생산 주문 경로에 연결되지 않는 현금 전용 ETF 연구 도구다. 사전 계획은 `strategy_v2_plan.md`, 결과는 `strategy_v2_data/results/`, 최종 보고는 프론트 저장소 `docs/ai-team/tasks/TEAM-11-VALIDATION.md`다. **12회 모두 S&P500 초과수익 미달, 개발조건을 통과한 후보 없음.**

Python 표준 라이브러리만 사용한다. 저장된 CSV로 실행하며 서버·DB·계좌·인증을 사용하지 않는다. 자료 취득 스크립트만 Yahoo 공개 자료에 접근한다. 실제자료의 원본·변환·해시는 각 snapshot manifest와 data-audit에 남아 있다.

백엔드 tools 디렉터리에서 테스트:

```powershell
python -B -m unittest strategy_v2_test strategy_v2_audit_test
```

백엔드 루트에서 개별 실행 예시(기존 결과를 덮어쓰지 않는 새 prefix 사용):

```powershell
python -B tools/strategy_v2.py tools/strategy_v2_data/development/daily.csv --candidate A --start 2018-01-01 --end 2023-12-31 --output-prefix tools/strategy_v2_reproduction/A-development-10bp --lock-file tools/strategy_v2_plan.md --lock-sha256 eaa0827e0fc30a39ccd8d3abdde34e4832ce56dd5864206d5b1d4147a2ad9a8f
python -B tools/strategy_v2_audit.py tools/strategy_v2_reproduction/A-development-10bp --market tools/strategy_v2_data/development/daily.csv
```

25bp는 `--slippage-bps 20`을 추가한다(수수료 기본5bp). `strategy_v2_run.py`는 전체12회를 순차 실행하며 개발 감사 통과→선정 잠금 기록→선정후6회 순서를 강제한다. 이미 results 디렉터리가 있으면 중단하므로 기존 원본을 삭제하거나 덮어쓰지 않는다. 전체 재현은 별도 작업 사본에서 결과 경로만 새롭게 지정하고 원래 잠금과 구분해 기록한다. 공개 자료를 재다운로드하면 조정가격이 바뀔 수 있으므로 저장 snapshot으로 재현한다.

10%는 고점 대비 차단 발동값이며 실제 최대손실 보장이 아니다. 실행은 조정가격 소수단위 완전체결 모델이다. 2026 자료는 이번 실행으로 이미 관측했으므로 다음 전략의 미관측 검증기간으로 취급하지 않는다. 테스트 PASS는 원장 일관성을 뜻하며 수익성/실전 안전성 통과를 의미하지 않는다.
