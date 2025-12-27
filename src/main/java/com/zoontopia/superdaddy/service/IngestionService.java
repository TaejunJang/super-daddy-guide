package com.zoontopia.superdaddy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:parenting_guide.pdf")
    private Resource pdfResource;

    public IngestionService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public void run(String... args) throws Exception {
        String fileName = pdfResource.getFilename(); // "parenting_guide.pdf"

        // 1. 데이터 존재 여부 확인
        if (hasExistingData(fileName)) {
            logger.info("Vector store already contains data. Skipping ingestion.");
            return;
        }

        // 2. PDF 파일 확인
        if (!pdfResource.exists()) {
            logger.warn("parenting_guide.pdf not found. Skipping ingestion.");
            return;
        }

        try {
            // 3. PDF 로드
            List<Document> rawDocuments = loadRawDocuments(fileName);

            // 4. Gemini를 이용한 문서 전처리 (Batch)
            List<Document> cleanedDocuments = refineDocumentsWithGemini(rawDocuments);

            // 5. 문서 분할 및 메타데이터 추가
            List<Document> processedDocuments = splitAndEnrichDocuments(cleanedDocuments, fileName);

            // 6. Vector Store에 저장
            ingestToVectorStore(processedDocuments);

            logger.info("Ingestion completed successfully for file: {}", fileName);
        } catch (Exception e) {
            logger.error("Error during ingestion process: {}", e.getMessage(), e);
        }
    }

    // --- Helper Methods ---

    private boolean hasExistingData(String fileName) {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();
        Filter.Expression filter = filterExpressionBuilder.eq("source", fileName).build();

        var searchRequest = SearchRequest.builder()
                .query("any")
                .filterExpression(filter)
                .topK(1)
                .build();

        var existingDocs = vectorStore.similaritySearch(searchRequest);
        return !existingDocs.isEmpty();
    }

    private List<Document> loadRawDocuments(String fileName) {
        logger.info("Loading PDF file '{}'...", fileName);
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(pdfResource);
        return pdfReader.get();
    }

    private List<Document> refineDocumentsWithGemini(List<Document> rawDocuments) throws InterruptedException {
        int totalDocs = rawDocuments.size();
        int batchSize = 5;
        List<Document> cleanedDocuments = new ArrayList<>();

        logger.info("Starting Gemini refinement for {} pages (Batch Size: {})...", totalDocs, batchSize);

        for (int i = 0; i < totalDocs; i += batchSize) {
            int end = Math.min(i + batchSize, totalDocs);
            List<Document> batchDocs = rawDocuments.subList(i, end);
            List<String> batchTexts = batchDocs.stream().map(Document::getText).toList();

            logger.info("Refining batch {}-{} / {}", i + 1, end, totalDocs);

            // 배치 단위 전처리 요청
            List<String> refinedTexts = refineBatchText(batchTexts);

            // 결과 매핑
            for (int j = 0; j < batchDocs.size(); j++) {
                String text = (j < refinedTexts.size()) ? refinedTexts.get(j) : batchDocs.get(j).getText();
                // 안전 장치: 빈 텍스트나 null 처리
                if (text == null || text.isBlank()) {
                    text = batchDocs.get(j).getText().replaceAll("\\s+", " ").trim();
                }
                cleanedDocuments.add(new Document(text, batchDocs.get(j).getMetadata()));
            }

            // Rate Limit 준수 (1초 대기)
            Thread.sleep(1000);
        }
        return cleanedDocuments;
    }

    private List<String> refineBatchText(List<String> originalTexts) {
        String systemPrompt = """
                                당신은 RAG(Retrieval-Augmented Generation) 시스템의 검색 품질을 높이기 위한 데이터 정제 및 레이아웃 복구 전문가입니다.
                                입력되는 JSON 배열의 각 요소는 2단 구성(좌우 분할) PDF에서 추출되어 문장 순서가 가로 방향으로 섞여 있는 상태입니다.
                            
                                [핵심 작업 지시]
                                1. **레이아웃 복구**: 가로로 읽혀서 섞여버린 왼쪽 단과 오른쪽 단의 문장들을 문맥에 맞게 원래의 순서대로 다시 재배치하세요.
                                2. **문장 완성**: '...집니다', '...심해' 처럼 줄바꿈으로 인해 잘린 단어들을 앞뒤 문맥을 고려하여 자연스럽게 연결하세요.
                                3. **텍스트 정제**: 불필요한 특수기호, 깨진 글자, 중복 공백을 제거하고 가독성 좋은 문단 구조로 정리하세요.
                                4. **데이터 보존**: 원문의 의미를 절대 요약, 생략, 수정하지 마세요. 모든 정보는 그대로 유지해야 합니다.
                                
                                [출력 규칙 - 절대 준수]
                                1. **구조 유지**: 반드시 입력 배열과 **동일한 순서, 동일한 크기(길이)**의 JSON 문자열 배열 형식을 유지해야 합니다.
                                2. **형식 제한**: Markdown 코드 블록(```json 등)을 절대 사용하지 마세요. 오직 순수한 JSON 배열(`["텍스트1", "텍스트2"]`)만 출력하세요.
                                3. **내용**: 설명이나 인사 없이 결과 데이터만 반환하세요.
                            
                                예시 입력: ["산전우울증 산후우울증 무슨 일이든 처음 겪는다면 크고 작은 스트레스를 유발합니다. 사람은 누구나 우울감을 느끼고, 산모도 예외는 아닙니다. 실 특히 여성에게 임신, 게다가 첫 임신이라면 엄청난 스트레스를 제로 많은 산모가 아기가 태어난 후에 이전보다 감정 기복이 동반합니다."]
                                예시 출력: ["산전우울증 및 산후우울증은 처음 겪는 일이라면 누구나 크고 작은 스트레스를 유발합니다. 특히 여성에게 첫 임신은 엄청난 스트레스를 동반하며, 실제로 많은 산모가 아기가 태어난 후에 이전보다 감정 기복이 심해진다고 호소합니다."]
                                """;

        try {
            String jsonInput = objectMapper.writeValueAsString(originalTexts);

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(jsonInput)
                    .call()
                    .content();

            String cleanJson = response.replaceAll("```json", "").replaceAll("```", "").trim();
            return objectMapper.readValue(cleanJson, new TypeReference<List<String>>() {});

        } catch (Exception e) {
            logger.warn("Gemini batch refinement failed. Falling back to simple cleanup. Error: {}", e.getMessage());
            return originalTexts.stream()
                    .map(s -> s.replaceAll("\\s+", " ").trim())
                    .toList();
        }
    }

    private List<Document> splitAndEnrichDocuments(List<Document> cleanedDocuments, String fileName) {
        logger.info("Splitting documents and adding metadata...");
        var tokenTextSplitter = new TokenTextSplitter(400, 100, 10, 5000, true);
        var documents = tokenTextSplitter.apply(cleanedDocuments);

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            doc.getMetadata().put("source", fileName);
            doc.getMetadata().put("chunk_index", i);
        }
        return documents;
    }

    private void ingestToVectorStore(List<Document> documents) throws InterruptedException {
        logger.info("Ingesting {} documents into Vector Store...", documents.size());
        int batchSize = 5;
        long delayMillis = 10000;

        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            vectorStore.accept(documents.subList(i, end));
            
            if (end < documents.size()) {
                Thread.sleep(delayMillis);
            }
        }
    }
}
