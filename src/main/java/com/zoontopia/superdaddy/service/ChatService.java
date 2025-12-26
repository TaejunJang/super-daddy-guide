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
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final double SIMILARITY_THRESHOLD = 0.6;

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    public String chat(String message) {
        // 1. 유사 문서 검색 (Top 1을 기준으로 문맥 확장)
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(message)
                        .topK(1)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build()
        );

        String context = "";
        if (!similarDocuments.isEmpty()) {
            Document topDoc = similarDocuments.get(0);
            
            // 메타데이터에서 안전하게 Integer 변환 (Long일 수 있음)
            Number chunkIndexNum = (Number) topDoc.getMetadata().get("chunk_index");
            if (chunkIndexNum == null) {
                // chunk_index가 없는 경우 예외 처리 (그냥 현재 문서만 사용하거나 로그 남김)
                logger.warn("문서에 chunk_index 메타데이터가 없습니다.");
                context = topDoc.getText();
            } else {
                int centerIndex = chunkIndexNum.intValue();
                String source = (String) topDoc.getMetadata().get("source");

                // 앞뒤 문맥(인덱스 -1, 0, +1) 가져오기
                List<Document> contextWindow = new ArrayList<>();
                
                FilterExpressionBuilder eb = new FilterExpressionBuilder();
                // 같은 소스 파일에서 인덱스가 centerIndex 주변인 것들 검색
                Filter.Expression filter = eb.and(
                        eb.eq("source", source),
                        eb.in("chunk_index", centerIndex - 1, centerIndex, centerIndex + 1)
                ).build();

                contextWindow = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(message) // 쿼리는 그대로 사용하되 필터로 범위를 좁힘
                                .topK(5)
                                .filterExpression(filter)
                                .build()
                );

                // 인덱스 순서대로 정렬하여 문맥 연결
                context = contextWindow.stream()
                        .sorted(Comparator.comparingInt(d -> ((Number) d.getMetadata().get("chunk_index")).intValue()))
                        .map(Document::getText)
                        .collect(Collectors.joining("\n\n"));
                
                logger.info("문맥 확장 완료: 검색된 인덱스 {}, 확장된 조각 수 {}", centerIndex, contextWindow.size());
            }
        }

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
        // 람다식 내부에서 사용하기 위해 effectively final 변수로 할당
        String finalContext = context;
        
        // 컨텍스트가 비어있을 경우 "관련 정보 없음"을 전달하여 모델이 가이드북 부재를 인지하게 합니다.
        return chatClient.prompt()
                .system(s -> s.text(systemPromptText)
                        .param("context", finalContext.isEmpty() ? "제공된 가이드북에 관련 정보가 없습니다." : finalContext))
                .user(message)
                .call()
                .content();
    }
}
