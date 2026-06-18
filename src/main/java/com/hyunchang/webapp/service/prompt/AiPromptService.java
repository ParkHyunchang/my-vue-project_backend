package com.hyunchang.webapp.service.prompt;

import com.hyunchang.webapp.entity.AiPromptOverride;
import com.hyunchang.webapp.repository.AiPromptOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AiPromptService.class);

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

    // ── 시드(앱 시작 시) ─────────────────────────────────────────────────────

    /** 카탈로그의 7종 프롬프트 중 DB에 행이 없는 것을 기본 지침으로 시드한다(앱 시작 시 1회). */
    @Transactional
    public void seedDefaults() {
        int created = 0;
        for (PromptDefinition def : AiPromptCatalog.all()) {
            if (repository.existsByPromptKey(def.getKey())) continue;
            AiPromptOverride row = new AiPromptOverride(def.getKey(), def.getDefaultInstruction(), "system");
            repository.save(row);
            created++;
        }
        if (created > 0) {
            log.info("[AiPrompt] 기본 프롬프트 지침 {}건 DB 시드 완료", created);
        }
    }

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
     * 지침 저장. 7종 모두 DB에 행으로 관리하므로 행을 삭제하지 않고 항상 갱신한다.
     * 내용이 비어 있으면 기본 지침으로 채워 저장한다. 지침은 자유 텍스트라 변수 검증은 하지 않는다.
     */
    @Transactional
    public PromptAdminView saveOverride(String key, String content, String updatedBy) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        String toStore = (content == null || content.isBlank()) ? def.getDefaultInstruction() : content;
        AiPromptOverride entity = repository.findByPromptKey(key).orElseGet(AiPromptOverride::new);
        entity.setPromptKey(key);
        entity.setContent(toStore);
        entity.setUpdatedBy(updatedBy);
        repository.save(entity);
        return toView(def, entity);
    }

    /** 기본 지침으로 되돌리기 — 행 내용을 코드 기본 지침으로 갱신(행은 유지). */
    @Transactional
    public PromptAdminView resetOverride(String key, String updatedBy) {
        PromptDefinition def = AiPromptCatalog.get(key);
        if (def == null) {
            throw new IllegalArgumentException("알 수 없는 프롬프트 키: " + key);
        }
        AiPromptOverride entity = repository.findByPromptKey(key).orElseGet(AiPromptOverride::new);
        entity.setPromptKey(key);
        entity.setContent(def.getDefaultInstruction());
        entity.setUpdatedBy(updatedBy);
        repository.save(entity);
        return toView(def, entity);
    }

    private PromptAdminView toView(PromptDefinition def, AiPromptOverride override) {
        String current = override == null ? null : override.getContent();
        boolean hasContent = current != null && !current.isBlank();
        // 커스텀 여부 = 저장된 지침이 코드 기본 지침과 다른가
        boolean customized = hasContent && !current.strip().equals(nz(def.getDefaultInstruction()).strip());
        return new PromptAdminView(
            def,
            hasContent ? current : null,
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
