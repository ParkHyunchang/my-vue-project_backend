# TEAM-13 시장 회복 재진입 연구

CPPI에서 투자비중이 극도로 작아져 회복하지 못하는 문제를 연구하는 오프라인 도구다. 운영 코드에 연결하지 않는다. 현금99% 주문 한도·누적고점·10% 영구차단을 보존하고, 시장회복신호와 시험진입으로 위험자산 비중을 단계적으로 복원한다. 규칙은 `strategy_v4_plan.md`이며 원본 v3 파일/결과는 수정하지 않는다.

표준 Python만 사용한다. 저장된 공개가격 `strategy_v3_data/daily.csv`와 v3 D/F 결과를 비교한다. 서버·계좌·인증·운영DB·네트워크를 사용하지 않는다.

tools 디렉터리에서 합성 테스트:

```powershell
python -B -m unittest strategy_v4_test strategy_v4_audit_test
```

백엔드 루트에서 고정20회실험:

```powershell
python -B tools/strategy_v4_run.py
```

runner는 코드·계획·자료·v3비교원장 해시를 잠그고 독립감사 통과후 다음 실행으로 진행한다. 결과는 `strategy_v4_results/`다. 기존 결과가 있으면 덮어쓰지 않고 중단한다. 별도재현에는새출력경로를 사용한다:

```powershell
python -B tools/strategy_v4.py tools/strategy_v3_data/daily.csv --candidate G --start 2008-01-01 --end 2017-12-31 --output-prefix tools/strategy_v4_reproduction/G-crisis-10bp --lock-file tools/strategy_v4_plan.md --lock-sha256 f871afa11f1e10b072f3d70795043d86b57cb7ba21b6175138190b867f6c21e3
python -B tools/strategy_v4_audit.py tools/strategy_v4_reproduction/G-crisis-10bp --market tools/strategy_v3_data/daily.csv
```

스트레스는 `--slippage-bps 20` 추가(수수료5bp는동일). 모든기간은이미관측한회고이고 기간끼리겹친다. v3대비재진입과노출이늘어도10%hard에더빨리도달하면성공으로평가하지않는다. 종가최대낙폭은장중위험을포함하지않고 차단10%는실제손실보장이아니다. 결과/회복지표/한계는 프론트 `docs/ai-team/tasks/TEAM-13-VALIDATION.md`에서보고한다.
