package com.zoontopia.superdaddy.service;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

import java.util.List;

@AiService
public interface EntityExtractor {

    @UserMessage("""
        다음 텍스트에서 육아 관련 핵심 키워드 5개를 추출하세요.
        결과는 다른 설명 없이 오직 '쉼표(,)로 구분된 목록'으로만 반환하세요.
        예: 기저귀, 수면 교육, 분유
        
        텍스트: {{text}}
        """)
    List<String> extractEntities(@V("text") String text);
}
