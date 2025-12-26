package com.zoontopia.superdaddy.service;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    // 유사도 점수 임계값 설정 (0.0 ~ 1.0 사이, 높을수록 엄격함)
    private static final double SIMILARITY_THRESHOLD = 0.75;

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    public String chat(String message) {
        // 1. 유사 문서 검색 (Top 3)
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(message)
                        .topK(3)
                        .similarityThreshold(0.75) // 유사도 점수 기준 설정 (0.0 ~ 1.0)
                        .build()
        );

        // 2. 검색 결과 로깅 및 컨텍스트 문자열 통합
        // 이미 기준을 통과한 데이터만 있으므로 중복 필터링 없이 맵핑만 수행합니다.
        String context = similarDocuments.stream()
                .peek(doc -> logger.info("검색된 문서 점수: {}, 내용 요약: {}...",
                        doc.getScore(),
                        doc.getText().substring(0, Math.min(doc.getText().length(), 25))))
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 3. 한국어 시스템 프롬프트 구성 (할루시네이션 방지 지침 포함)
        String systemPromptText = """
                당신은 '슈퍼대디'입니다. 초보 아빠들을 위한 친절하고 든든한 육아 조언자이자 친구 같은 존재입니다.
                
                [지침]
                1. 아래 제공된 [가이드북 컨텍스트]의 내용을 우선적으로 사용하여 질문에 답변하세요.
                2. 만약 [가이드북 컨텍스트]에 질문에 대한 직접적인 답이 없다면, "제공된 가이드북에는 관련 내용이 없지만, 일반적인 육아 지식에 기반하여 말씀드릴게요."라고 먼저 명시한 후 답변하세요.
                3. 답변은 친절하고 격려하는 말투(예: ~해요, ~해봐요)를 사용하세요.
                4. 의학적으로 위험한 상황(응급상황)에 대한 질문이라면 반드시 전문의 상담을 권고하세요.
                
                [가이드북 컨텍스트]
                {context}
                """;

        // 4. Gemini API 호출
        // 컨텍스트가 비어있을 경우 "관련 정보 없음"을 전달하여 모델이 가이드북 부재를 인지하게 합니다.
        return chatClient.prompt()
                .system(s -> s.text(systemPromptText)
                        .param("context", context.isEmpty() ? "제공된 가이드북에 관련 정보가 없습니다." : context))
                .user(message)
                .call()
                .content();
    }
}
