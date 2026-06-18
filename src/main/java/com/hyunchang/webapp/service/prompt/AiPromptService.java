package com.hyunchang.webapp.service.prompt;

import com.hyunchang.webapp.entity.AiPromptOverride;
import com.hyunchang.webapp.repository.AiPromptOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 프롬프트의 유효 템플릿 조회·렌더링·검증·저장을 담당.
 *
 * 동작 원칙:
 *  - 유효 템플릿 = DB 오버라이드(비어있지 않은 경우) 우선, 없으면 코드 기본값.
 *  - 저장 시점에 알 수 없는 {{변수}} 가 있으면 거부(관리자에게 즉시 알림).
 *  - 렌더링 시점에 혹시라도 깨지면(미지의 변수 등) 코드 기본값으로 폴백 → 분석이 멈추지 않음.
 */
@Service
public class AiPromptService {

    private static final Logger log = LoggerFactory.getLogger(AiPromptService.class);

    /** {{ 변수이름 }} — 한글/영문/숫자/언더스코어 허용, 앞뒤 공백 허용. */
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([\\p{L}\\p{N}_]+)\\s*\\}\\}");

    private final AiPromptOverrideRepository repository;

    public AiPromptService(AiPromptOverrideRepository repository) {
        this.repository = repository;
    }

    // ── 렌더링 (서비스에서 호출) ────────────────────────────────────────────

    /**
     * 주어진 프롬프트 키의 유효 템플릿에 변수 값을 끼워 최종 프롬프트 문자열을 만든다.
     * 오버라이드가 손상돼 있으면(미지의 변수 포함) 안전하게 기본 템플릿으로 폴백한다.
     */
    public String render(String key, Map<String, String> vars) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        String template = effectiveTemplate(def);

        // 오버라이드가 허용되지 않은 변수를 포함하면(잘못 저장됐을 가능성) 기본값으로 폴백
        if (!unknownVariables(template, def).isEmpty() && !template.equals(def.getDefaultTemplate())) {
            log.warn("[AiPrompt] '{}' 오버라이드에 알 수 없는 변수가 있어 기본 템플릿으로 폴백합니다.", key);
            template = def.getDefaultTemplate();
        }
        return substitute(template, vars);
    }

    /** {{name}} 토큰을 vars 값으로 치환. 값이 없는 토큰은 빈 문자열로 대체한다. */
    private String substitute(String template, Map<String, String> vars) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = vars == null ? null : vars.get(name);
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── 유효 템플릿 / 변수 검사 ─────────────────────────────────────────────

    /** DB 오버라이드(비어있지 않으면) 우선, 없으면 기본 템플릿. */
    private String effectiveTemplate(PromptDefinition def) {
        Optional<AiPromptOverride> override = repository.findByPromptKey(def.getKey());
        if (override.isPresent()) {
            String content = override.get().getContent();
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        return def.getDefaultTemplate();
    }

    /** 템플릿에서 정의에 없는(허용되지 않은) 변수 토큰 목록. 비어 있으면 유효. */
    public List<String> unknownVariables(String template, PromptDefinition def) {
        if (template == null) return List.of();
        Set<String> allowed = def.variableNames();
        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(template);
        while (m.find()) {
            String name = m.group(1);
            if (!allowed.contains(name)) unknown.add(name);
        }
        return new ArrayList<>(unknown);
    }

    // ── 관리(Admin) ────────────────────────────────────────────────────────

    /** 관리 화면용: 전체 프롬프트 정의 + 현재 오버라이드 상태를 합쳐 반환. */
    public List<PromptAdminView> getAdminViews() {
        List<PromptAdminView> views = new ArrayList<>();
        for (PromptDefinition def : AiPromptCatalog.all()) {
            Optional<AiPromptOverride> override = repository.findByPromptKey(def.getKey());
            String current = override.map(AiPromptOverride::getContent).orElse(null);
            boolean customized = current != null && !current.isBlank();
            views.add(new PromptAdminView(
                def,
                customized ? current : null,
                customized,
                override.map(AiPromptOverride::getUpdatedAt).map(Object::toString).orElse(null),
                override.map(AiPromptOverride::getUpdatedBy).orElse(null)
            ));
        }
        return views;
    }

    /**
     * 오버라이드 저장. 알 수 없는 변수가 있으면 IllegalArgumentException.
     * content 가 비어 있거나 기본값과 같으면 오버라이드를 제거(= 기본값으로 되돌림)한다.
     */
    @Transactional
    public PromptAdminView saveOverride(String key, String content, String updatedBy) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        // 비었거나 기본값과 동일 → 오버라이드 불필요(기본값 사용)
        if (content == null || content.isBlank() || content.strip().equals(def.getDefaultTemplate().strip())) {
            repository.deleteByPromptKey(key);
            return toView(def, null);
        }
        List<String> unknown = unknownVariables(content, def);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                "허용되지 않은 변수가 있습니다: " + String.join(", ", unknown)
                + " — 사용 가능한 변수만 {{ }} 로 넣어주세요.");
        }
        AiPromptOverride entity = repository.findByPromptKey(key).orElseGet(AiPromptOverride::new);
        entity.setPromptKey(key);
        entity.setContent(content);
        entity.setUpdatedBy(updatedBy);
        repository.save(entity);
        return toView(def, content);
    }

    /** 오버라이드 삭제(기본값으로 되돌리기). */
    @Transactional
    public PromptAdminView resetOverride(String key) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        repository.deleteByPromptKey(key);
        return toView(def, null);
    }

    private PromptAdminView toView(PromptDefinition def, String current) {
        boolean customized = current != null && !current.isBlank();
        return new PromptAdminView(def, customized ? current : null, customized, null, null);
    }

    /** 관리 화면 1행(프롬프트 정의 + 현재 상태). */
    public record PromptAdminView(
        PromptDefinition definition,
        String currentContent,   // 오버라이드 내용 (없으면 null → 기본값 사용 중)
        boolean customized,
        String updatedAt,
        String updatedBy
    ) {}
}
