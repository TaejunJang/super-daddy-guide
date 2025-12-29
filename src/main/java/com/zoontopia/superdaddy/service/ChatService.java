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

    // 리랭킹을 위해 후보군을 더 넓게 가져옵니다
    private static final int RETRIEVAL_TOP_K = 200;
    // text-embedding-004의 낮아진 스코어를 고려하여 임계치를 낮춤
    private static final double SIMILARITY_THRESHOLD = 0.30;


    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    public String chat(String message) {
        // 1. 후보군 검색
        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(message)
                        .topK(RETRIEVAL_TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build()
        );

        String context = "";

        if (!candidates.isEmpty()) {
            // 2. 연관성 있는 모든 문서 선택 (리스트 반환)
            List<Document> relevantDocs = selectRelevantDocuments(message, candidates);

            if (!relevantDocs.isEmpty()) {
                // 3. 선택된 문서들을 기반으로 통합 문맥 생성 (중복 제거 포함)
                context = expandAndMergeContext(message, relevantDocs);
            }
        }

        return generateFinalResponse(message, context);
    }

    /**
     * 리랭킹 로직: LLM에게 후보군 중 가장 질문에 적합한 문서의 인덱스를 묻습니다.
     */
    private Document reRankDocuments(String query, List<Document> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Document d = candidates.get(i);
            // 메타데이터의 키워드까지 LLM에게 전달하여 판단 근거 강화
            String keywords = d.getMetadata().getOrDefault("keywords", "").toString();
            sb.append(String.format("[%d] 제목: %s\n키워드: %s\n내용: %s\n\n",
                    i, d.getMetadata().get("section_title"), keywords, d.getText()));
        }

        String reRankSystemPrompt = """
                당신은 최적의 정보를 선택하는 리랭킹 전문가입니다.
                [사용자 질문]과 가장 의미론적으로 가깝고, 질문에 대한 구체적인 답변을 포함하고 있는 [후보군]의 번호를 하나만 골라주세요.
                
                선택 가이드라인:
                1. 질문의 핵심 키워드가 '제목'이나 '키워드'에 포함되어 있는지 확인하세요.
                2. 단순히 단어가 겹치는 것보다 질문의 의도(예: 단계별 특징, 방법론 등)를 충족하는 내용을 우선하세요.
                3. 관련 내용이 전혀 없다면 'NONE'이라고 답하세요.
                
                오직 번호(예: 0) 혹은 'NONE'만 출력하세요.
                """;

        String response = chatClient.prompt()
                .system(reRankSystemPrompt)
                .user(String.format("질문: %s\n\n[후보군]\n%s", query, sb.toString()))
                .call()
                .content();

        try {
            String bestIndexStr = response.toUpperCase().trim();
            if (bestIndexStr.contains("NONE")) return null;

            // 숫자만 추출하는 정규식
            int bestIndex = Integer.parseInt(bestIndexStr.replaceAll("[^0-9]", ""));

            if (bestIndex >= 0 && bestIndex < candidates.size()) {
                logger.info("리랭킹 완료: 선택된 인덱스 {} (Score: {})", bestIndex, candidates.get(bestIndex).getScore());
                return candidates.get(bestIndex);
            }
        } catch (Exception e) {
            logger.warn("리랭킹 파싱 실패: {}. 1순위 문서 사용.", response);
        }
        return candidates.get(0);
    }

    private String expandContext(String message, Document topDoc) {
        // 기존 로직 유지하되, 쿼리에 Prefix 추가하여 일관성 유지
        Number chunkIndexNum = (Number) topDoc.getMetadata().get("chunk_index");
        if (chunkIndexNum == null) return topDoc.getText();

        int centerIndex = chunkIndexNum.intValue();
        String source = (String) topDoc.getMetadata().get("source");

        FilterExpressionBuilder eb = new FilterExpressionBuilder();
        Filter.Expression filter = eb.and(
                eb.eq("source", source),
                eb.in("chunk_index", centerIndex - 1, centerIndex, centerIndex + 1)
        ).build();

        List<Document> contextWindow = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(message) // Prefix 추가
                        .topK(5)
                        .filterExpression(filter)
                        .build()
        );

        return contextWindow.stream()
                .sorted(Comparator.comparingInt(d -> ((Number) d.getMetadata().get("chunk_index")).intValue()))
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 리랭킹 로직 수정: 관련 있는 모든 문서의 인덱스를 리스트로 받습니다.
     */
    private List<Document> selectRelevantDocuments(String query, List<Document> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Document d = candidates.get(i);
            String keywords = d.getMetadata().getOrDefault("keywords", "").toString();
            sb.append(String.format("[%d] 제목: %s\n키워드: %s\n내용: %s\n\n",
                    i, d.getMetadata().get("section_title"), keywords, d.getText()));
        }

        String reRankSystemPrompt = """
                당신은 정보 필터링 전문가입니다.
                [사용자 질문]과 관련이 있는 모든 [후보군]의 번호를 골라주세요.
                
                선택 기준:
                1. 질문에 직접적인 해답을 주거나, 답변을 구성하는 데 도움이 되는 보조 정보를 포함하는 경우.
                2. 서로 다른 측면(예: 하나는 원인, 하나는 해결책)을 다루고 있다면 모두 선택하세요.
                3. 관련이 없는 문서는 과감히 제외하세요.
                
                오직 번호들을 쉼표로 구분하여 출력하세요 (예: 0, 3, 5). 
                관련 문서가 하나도 없다면 'NONE'이라고 답하세요.
                """;

        String response = chatClient.prompt()
                .system(reRankSystemPrompt)
                .user(String.format("질문: %s\n\n[후보군]\n%s", query, sb.toString()))
                .call()
                .content().toUpperCase().trim();

        List<Document> result = new ArrayList<>();
        if (response.contains("NONE")) return result;

        try {
            // 쉼표로 구분된 인덱스 파싱
            String[] indices = response.split(",");
            for (String indexPart : indices) {
                int idx = Integer.parseInt(indexPart.replaceAll("[^0-9]", ""));
                if (idx >= 0 && idx < candidates.size()) {
                    result.add(candidates.get(idx));
                }
            }
            logger.info("리랭킹 완료: {}개의 문서 선택됨", result.size());
        } catch (Exception e) {
            logger.warn("인덱스 파싱 실패: {}. 1순위 문서만 사용.", response);
            result.add(candidates.get(0));
        }
        return result;
    }


    /**
     * 여러 문서의 문맥을 확장하고 중복을 제거하여 결합합니다.
     */
    private String expandAndMergeContext(String message, List<Document> relevantDocs) {
        // 중복 청크 방지를 위해 Map 사용 (Key: Document ID 또는 특정 메타데이터 조합)
        Map<String, Document> mergedContextMap = new java.util.LinkedHashMap<>();

        for (Document doc : relevantDocs) {
            Number chunkIndexNum = (Number) doc.getMetadata().get("chunk_index");
            if (chunkIndexNum == null) {
                mergedContextMap.put(doc.getId(), doc);
                continue;
            }

            int centerIndex = chunkIndexNum.intValue();
            String source = (String) doc.getMetadata().get("source");
            String parentDocId = (String) doc.getMetadata().get("parent_document_id");

            // 해당 청크의 앞뒤 윈도우 조회
            FilterExpressionBuilder eb = new FilterExpressionBuilder();
            Filter.Expression filter = eb.and(
                    eb.eq("parent_document_id", parentDocId),
                    eb.in("chunk_index", centerIndex - 1, centerIndex, centerIndex + 1)
            ).build();

            List<Document> window = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(message)
                            .topK(5)
                            .filterExpression(filter)
                            .build()
            );

            // 결과 취합 (이미 포함된 청크는 무시됨)
            for (Document wDoc : window) {
                // chunk_index와 source의 조합으로 유니크 키 생성
                String key = source + "_" + wDoc.getMetadata().get("chunk_index");
                mergedContextMap.putIfAbsent(key, wDoc);
            }
        }

        // chunk_index 순서대로 정렬하여 가독성 확보
        return mergedContextMap.values().stream()
                .sorted(Comparator.comparing((Document d) -> d.getMetadata().get("source").toString())
                        .thenComparingInt((Document d) -> ((Number) d.getMetadata().get("chunk_index")).intValue()))
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    private String generateFinalResponse(String message, String context) {
        String systemPromptText = """
                초보 아빠들을 위한 친절하고 든든한 육아 조언자이자 친구 같은 존재입니다.
                
                [지침]
                1. 아래 제공된 [가이드북 컨텍스트]의 내용을 우선적으로 사용하여 질문에 답변하세요.
                2. 만약 [가이드북 컨텍스트]에 질문에 대한 직접적인 답이 없다면, "제공된 가이드북에는 관련 내용이 없지만, 일반적인 육아 지식에 기반하여 말씀드릴게요."라고 먼저 명시한 후 답변하세요.
                3. 답변은 친절하고 격려하는 말투(예: ~해요, ~해봐요)를 사용하세요.
                4. 의학적으로 위험한 상황(응급상황)에 대한 질문이라면 반드시 전문의 상담을 권고하세요.
                
                [가이드북 컨텍스트]
                {context}
                """;

        return chatClient.prompt()
                .system(s -> s.text(systemPromptText)
                        .param("context", context.isEmpty() ? "제공된 가이드북에 관련 정보가 없습니다." : context))
                .user(message)
                .call()
                .content();
    }
}
