# TEAM-12 고점 추종 위험예산 연구

운영 주문 코드에 연결되지 않는 Python 표준라이브러리 도구다. 현금 한도·10% 영구 차단을 유지하며 D(SPY), E(QQQ추세), F(주식/금/국채 모멘텀 분산)에 고점90% floor를 기준으로 계산한 투자한도를 적용한다. `strategy_v3_plan.md`가 사전 고정 계약이며 코드와 계획 해시가 실행 결과에 남는다.

`strategy_v3_data/`는 공개 Yahoo 가격과 원본·변환·해시다. `strategy_v3_data_fetch.py`만 공개가격 네트워크를 사용한다. 연구 엔진/원장 감사는 파일만 읽으며 서버·키·운영DB·실계좌를 사용하지 않는다. 이전 v2 결과는 수정하지 않는다.

tools 디렉터리에서:

```powershell
python -B -m unittest strategy_v3_test strategy_v3_audit_test
```

백엔드 루트에서 전체 고정30회를 실행하는 명령:

```powershell
python -B tools/strategy_v3_run.py
```

이미 `strategy_v3_data/results`가 있으면 runner는 중단한다. 원래 결과를 삭제/덮어쓰지 말고 재현용 별도 사본에서 결과 경로를 바꿔 기록한다. 개별 재현은 새로운 출력경로로 실행한다:

```powershell
python -B tools/strategy_v3.py tools/strategy_v3_data/daily.csv --candidate D --start 2008-01-01 --end 2017-12-31 --output-prefix tools/strategy_v3_reproduction/D-crisis-10bp --lock-file tools/strategy_v3_plan.md --lock-sha256 f589cf061a1a51d29e0aadb6d7d55520d0c742660d6fd82be86a8e00895eb22d
python -B tools/strategy_v3_audit.py tools/strategy_v3_reproduction/D-crisis-10bp --market tools/strategy_v3_data/daily.csv
```

스트레스는 `--slippage-bps 20`(기본 수수료5bp)을 더한다. 조정가격이 사후 바뀔 수 있으므로 재현에는 새 다운로드보다 저장 snapshot을 쓴다. `manifest`/`data-audit`의 성과 미열람 표시는 자료 취득 시점이며 실행 이력은 results의 pre-run-lock/completion을 따른다.

전기간을 이미 관측한 회고로 취급한다. 30회 구간은 겹치므로 독립표본이 아니며 좋은 기간만 선택하지 않는다. 감사 PASS는 원장 정합성을 뜻하며 초과수익 입증이 아니다. floor와10%차단은 실제 손실 상한을 보장하지 않는다. 일봉·조정가격·소수단위·전량체결·무이자현금·세전 가정이다. 최종 성과 판정은 프론트 저장소 `docs/ai-team/tasks/TEAM-12-VALIDATION.md`를 따른다.
