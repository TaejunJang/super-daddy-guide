package com.zoontopia.superdaddy.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface TextCleaner {

    @SystemMessage("""
            당신은 PDF에서 추출된 텍스트를 정제하는 전문 편집자입니다.
            당신의 목표는 원본의 의미와 내용을 절대 훼손하지 않으면서, 기계적으로 발생하는 포맷 오류만 수정하는 것입니다.
            """)
    @UserMessage("""
            다음은 육아 가이드 PDF에서 추출된 원본 텍스트입니다. 아래 지침에 따라 깨끗하게 정제해 주세요.
            
            지침:
            1. PDF 추출 시 발생하는 잡음(페이지 번호, 상단/하단 머리말, 단순 목차 선 등)을 제거하세요.
            2. 다단 구성(왼쪽/오른쪽 나뉨)으로 인해 문장이 중간에 끊긴 경우, 이를 자연스럽게 연결하세요.
            3. 불필요한 연속 공백을 줄이고, 들여쓰기를 표준화하세요.
            4. 의미 없는 특수문자나 구분선(예: '---', 'Page 12')을 제거하세요.
            5. **주의**: 내용을 요약하거나 문체를 바꾸지 마세요. 원본의 한국어 텍스트와 의미를 그대로 유지해야 합니다.
            6. 오직 정제된 결과 텍스트만 반환하세요.
            
            원본 텍스트:
            {{text}}
            """)
    String cleanText(@V("text") String text);
}