package com.hyunchang.webapp.service.portfolio;

import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import java.util.Locale;

public enum PortfolioAccountType {
    STOCK(
            "stock",
            "주식",
            AiPromptCatalog.PORTFOLIO_ANALYSIS,
            "삼성증권 주식계좌입니다. 국내·미국 주식을 장기 보유하는 계좌입니다. 성장성, 리스크, 분산, 비중 조정을 중심으로 분석합니다.",
            "추가 출력 지침: 이 보고서는 삼성증권 주식계좌(국내·미국 장기 투자) 진단입니다. "
                    + "코어 종목은 장기 적립 관점으로, 위성 종목은 단기 매매 관점으로 구분해 분석하세요. "
                    + "위 투자자 운용 기준(코어/위성 태그)에 따라 종목별 액션을 판단하세요.\n"),
    ISA(
            "isa",
            "ISA",
            AiPromptCatalog.PORTFOLIO_ISA_ANALYSIS,
            "신한투자증권 ISA 계좌입니다. 중장기 적립식 운용 전략으로 계속 모아가는 계좌입니다. 2026-06-24 신규 개설한 서민형 ISA 기본정보는 내부 판단 기준으로만 사용하고, 세제·의무기간 판단에 직접 필요할 때만 언급합니다.",
            "추가 출력 지침: 이 보고서는 반드시 신한투자증권 ISA 계좌의 중장기 적립식 진단으로 작성하세요. "
                    + "절세 계좌 운용을 다루는 최고 수준의 전문가 관점으로, 중장기 적립·분할매수·세제 효율을 최우선으로 점검하세요. "
                    + "단기 매매보다 정기 적립과 장기 보유를 원칙으로 조언하세요. "
                    + "CASH 자산은 손익률이 아니라 추가 적립 대기 자금과 리밸런싱 재원으로 해석하세요. "
                    + "ISA 개설일·의무가입기간·서민형 비과세 한도는 기본 전제로만 깔아두고, 매도/세제 유의점 판단에 직접 필요할 때만 짧게 언급하세요. "
                    + "필요하지 않으면 ISA 기본정보를 별도 문단으로 반복하지 마세요. "
                    + "근거가 부족하면 억지로 채우지 말고 '해당 없음'이라고 쓰세요.\n"),
    GENERAL(
            "general",
            "종합계좌",
            AiPromptCatalog.PORTFOLIO_GENERAL_ANALYSIS,
            "신한투자증권 종합계좌입니다. 1종목당 200~300만원 소액으로 단기 스윙 매매를 하는 계좌입니다. 장기 보유 원칙 없이 모멘텀·뉴스 기반으로 익절·손절을 적극 활용합니다.",
            "추가 출력 지침: 이 보고서는 반드시 신한투자증권 종합계좌의 단기매매 진단으로 작성하세요. "
                    + "장기 보유 원칙 없이, 뉴스 모멘텀·일변동률·평가손익률을 기준으로 익절·부분익절·손절·관망을 적극 권고하세요. "
                    + "'장기 보유 시 회복 가능'이라는 논리로 손절을 미루지 마세요. "
                    + "각 종목의 구체적인 손절가와 익절가 수준(평단 대비 %)을 반드시 제시하세요. "
                    + "신규 매수 후보는 시장 뉴스에서 강한 단기 모멘텀이 보일 때만 제시하고, 근거가 없으면 '해당 없음'으로 쓰세요.\n"),
    IRP(
            "irp",
            "퇴직연금 IRP",
            AiPromptCatalog.PORTFOLIO_IRP_ANALYSIS,
            "신한은행 IRP 계좌입니다. 은퇴자산 장기 적립 계좌로 계속 모아가는 전략입니다. 위험자산 70% 한도와 안전자산 약 30% 필요 비중은 내부 판단 기준으로만 사용하고, 리밸런싱 판단에 직접 필요할 때만 언급합니다.",
            "추가 출력 지침: 이 보고서는 반드시 신한은행 IRP 퇴직연금 계좌 진단으로 작성하세요. "
                    + "퇴직연금 제도와 연금자산 배분을 관리하는 최고 수준의 전문가 관점으로, 은퇴자산의 장기 안정성·분산·변동성 방어·현금성 자산 비중을 최우선으로 평가하세요. "
                    + "CASH 자산은 손익률이 아니라 안전자산/대기자금/리밸런싱 완충 역할로 해석하세요. "
                    + "위험자산 70% 한도와 안전자산 약 30% 기준은 기본 전제로만 깔아두고, 추가매수 가능 여부나 리밸런싱 우선순위에 직접 영향을 줄 때만 짧게 언급하세요. "
                    + "필요하지 않으면 IRP 한도 규칙을 별도 문단으로 반복하지 마세요. "
                    + "보고서 마지막 실전 섹션에는 IRP에 추가매수할 만한 후보와 손절/축소 검토 후보를 구분해 제시하세요. "
                    + "IRP 한도상 추가매수가 어려우면 그 후보는 관망 또는 대기 후보로 분류하고, 근거가 부족하면 '해당 없음'이라고 쓰세요.\n"),
    ALL(
            "all",
            "전체 계좌(종합)",
            AiPromptCatalog.PORTFOLIO_ALL_ANALYSIS,
            "삼성증권 주식계좌입니다. 국내·미국 주식을 장기 보유하는 계좌입니다. 성장성, 리스크, 분산, 비중 조정을 중심으로 분석합니다.",
            "추가 출력 지침: 이 보고서는 장기(삼성증권)·단기(신한종합)·ISA(신한투자증권)·IRP(신한은행) 4개 계좌를 합친 통합 진단입니다. "
                    + "각 계좌는 서로 다른 운용 전략을 따릅니다 — 장기: 코어/위성 장기보유, 단기: 스윙 매매(적극적 손절·익절), "
                    + "ISA: 절세 중장기 적립식, IRP: 은퇴자산 안정성(위험자산 70% 한도, 안전자산 30% 목표). "
                    + "한 계좌의 판단 기준을 다른 계좌 보유분에 그대로 적용하지 마세요 (예: 단기계좌 기준 손절 논리를 장기계좌 동일 종목에 적용 금지). "
                    + "종목별 분석에서는 반드시 각 종목이 어느 계좌 소속인지 표기하고, 그 계좌의 전략 기준으로만 판단하세요. "
                    + "동일 종목 또는 동일 섹터가 여러 계좌에 걸쳐 있으면 전체 자산 기준 노출 비중이 과도한지 별도 섹션에서 짚으세요. "
                    + "근거가 부족하면 억지로 채우지 말고 '해당 없음'이라고 쓰세요.\n");

    private final String code;
    private final String defaultLabel;
    private final String promptKey;
    private final String defaultNote;
    private final String additionalInstruction;

    PortfolioAccountType(
            String code,
            String defaultLabel,
            String promptKey,
            String defaultNote,
            String additionalInstruction) {
        this.code = code;
        this.defaultLabel = defaultLabel;
        this.promptKey = promptKey;
        this.defaultNote = defaultNote;
        this.additionalInstruction = additionalInstruction;
    }

    public String code() {
        return code;
    }

    public String defaultLabel() {
        return defaultLabel;
    }

    public String promptKey() {
        return promptKey;
    }

    public String defaultNote() {
        return defaultNote;
    }

    public String additionalInstruction() {
        return additionalInstruction;
    }

    public static PortfolioAccountType parse(String raw) {
        if (raw == null || raw.isBlank()) return STOCK;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (PortfolioAccountType type : values()) {
            if (type.code.equals(normalized)) return type;
        }
        return STOCK;
    }
}
