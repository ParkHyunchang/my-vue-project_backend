package com.hyunchang.webapp.service.prompt;

import com.hyunchang.webapp.entity.AiPromptOverride;
import com.hyunchang.webapp.repository.AiPromptOverrideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 프롬프트 조립·지침 오버라이드 저장 담당.
 *
 * 최종 프롬프트 = [지침(instruction)] + [고정 데이터(fixedContext)] + [고정 응답스키마(fixedSchema)].
 *  - 지침은 관리자가 화면에서 수정하는 유일한 부분. 오버라이드(비어있지 않으면) 우선, 없으면 코드 기본 지침.
 *  - 데이터/스키마는 코드 고정. {{변수}} 는 서비스가 넘긴 값으로 치환된다(조립 후 전체에 대해 1회 치환).
 *  - 지침은 자유 텍스트라 별도 검증 없음. 비어 있으면 기본 지침으로 동작.
 */
@Service
public class AiPromptService {

    /** {{ 변수이름 }} — 한글/영문/숫자/언더스코어, 앞뒤 공백 허용. JSON 의 단일 중괄호 { } 와는 충돌하지 않는다. */
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([\\p{L}\\p{N}_]+)\\s*\\}\\}");

    private final AiPromptOverrideRepository repository;

    public AiPromptService(AiPromptOverrideRepository repository) {
        this.repository = repository;
    }

    // ── 렌더링 (서비스에서 호출) ────────────────────────────────────────────

    /** 지침 + 고정 데이터 + 고정 스키마를 조립하고 {{변수}} 를 값으로 치환해 최종 프롬프트를 만든다. */
    public String render(String key, Map<String, String> vars) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        String assembled = nz(effectiveInstruction(def)).strip()
            + "\n\n" + nz(def.getFixedContext()).strip()
            + "\n\n" + nz(def.getFixedSchema()).strip();
        return substitute(assembled, vars);
    }

    /** {{name}} 토큰을 vars 값으로 치환. 값이 없는 토큰은 빈 문자열로 대체. */
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

    /** DB 오버라이드(비어있지 않으면) 우선, 없으면 기본 지침. */
    private String effectiveInstruction(PromptDefinition def) {
        Optional<AiPromptOverride> override = repository.findByPromptKey(def.getKey());
        if (override.isPresent()) {
            String content = override.get().getContent();
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        return def.getDefaultInstruction();
    }

    private String nz(String s) { return s == null ? "" : s; }

    // ── 관리(Admin) ────────────────────────────────────────────────────────

    /** 관리 화면용: 전체 프롬프트 정의 + 현재 지침 오버라이드 상태. */
    public List<PromptAdminView> getAdminViews() {
        List<PromptAdminView> views = new ArrayList<>();
        for (PromptDefinition def : AiPromptCatalog.all()) {
            views.add(toView(def, repository.findByPromptKey(def.getKey()).orElse(null)));
        }
        return views;
    }

    /**
     * 지침 오버라이드 저장. 비어 있거나 기본 지침과 같으면 오버라이드를 제거(= 기본값 사용).
     * 지침은 자유 텍스트라 변수 검증은 하지 않는다.
     */
    @Transactional
    public PromptAdminView saveOverride(String key, String content, String updatedBy) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        if (content == null || content.isBlank()
                || content.strip().equals(nz(def.getDefaultInstruction()).strip())) {
            repository.deleteByPromptKey(key);
            return toView(def, null);
        }
        AiPromptOverride entity = repository.findByPromptKey(key).orElseGet(AiPromptOverride::new);
        entity.setPromptKey(key);
        entity.setContent(content);
        entity.setUpdatedBy(updatedBy);
        repository.save(entity);
        return toView(def, entity);
    }

    /** 오버라이드 삭제(기본 지침으로 되돌리기). */
    @Transactional
    public PromptAdminView resetOverride(String key) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        repository.deleteByPromptKey(key);
        return toView(def, null);
    }

    private PromptAdminView toView(PromptDefinition def, AiPromptOverride override) {
        String current = override == null ? null : override.getContent();
        boolean customized = current != null && !current.isBlank();
        return new PromptAdminView(
            def,
            customized ? current : null,
            customized,
            override == null || override.getUpdatedAt() == null ? null : override.getUpdatedAt().toString(),
            override == null ? null : override.getUpdatedBy()
        );
    }

    /** 관리 화면 1행(프롬프트 정의 + 현재 지침 상태). currentContent 는 지침 오버라이드(없으면 null). */
    public record PromptAdminView(
        PromptDefinition definition,
        String currentContent,
        boolean customized,
        String updatedAt,
        String updatedBy
    ) {}
}
