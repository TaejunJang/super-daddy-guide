package com.zoontopia.superdaddy.service;

import com.zoontopia.superdaddy.domain.entity.ChunkNode;
import com.zoontopia.superdaddy.repository.ChunkRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatLanguageModel chatLanguageModel;
    private final ChunkRepository chunkRepository;
    private final EmbeddingModel embeddingModel;
    private final EntityExtractor entityExtractor;

    public String chat(String message) {
        // 1. 질문 임베딩
        Response<Embedding> embeddingResponse = embeddingModel.embed(message);
        List<Float> floatEmbedding = embeddingResponse.content().vectorAsList();
        
        // Neo4j 드라이버 호환성을 위해 Float -> Double 변환
        List<Double> embedding = floatEmbedding.stream()
                .map(Float::doubleValue)
                .collect(Collectors.toList());

        // 2. 키워드 추출
        List<String> keywords = entityExtractor.extractEntities(message);
        log.info("Extracted keywords: {}", keywords);

        // 3. 하이브리드 검색
        // 벡터 검색 + 엔티티 매칭
        List<ChunkNode> chunks = chunkRepository.findHybrid(embedding, keywords);

        // 4. 문맥 구성
        String context = chunks.stream()
                .map(ChunkNode::getContent)
                .collect(Collectors.joining("\n\n"));

        // 5. 답변 생성
        return generateFinalResponse(message, context);
    }

    private String generateFinalResponse(String message, String context) {
        String systemPromptTemplate = """
                초보 아빠들을 위한 친절하고 든든한 육아 조언자이자 친구 같은 존재입니다.
                
                [지침]
                1. 아래 제공된 [가이드북 컨텍스트]의 내용을 우선적으로 사용하여 질문에 답변하세요.
                2. 만약 [가이드북 컨텍스트]에 질문에 대한 직접적인 답이 없다면, \"제공된 가이드북에는 관련 내용이 없지만, 일반적인 육아 지식에 기반하여 말씀드릴게요.\"라고 먼저 명시한 후 답변하세요.
                3. 답변은 친절하고 격려하는 말투(예: ~해요, ~해봐요)를 사용하세요.
                4. 의학적으로 위험한 상황(응급상황)에 대한 질문이라면 반드시 전문의 상담을 권고하세요.
                
                [가이드북 컨텍스트]
                {{context}}
                """;
        
        String finalSystemMessage = systemPromptTemplate.replace("{{context}}", 
                (context == null || context.isEmpty()) ? "제공된 가이드북에 관련 정보가 없습니다." : context);

        Response<AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from(finalSystemMessage),
                UserMessage.from(message)
        );

        return response.content().text();
    }
}