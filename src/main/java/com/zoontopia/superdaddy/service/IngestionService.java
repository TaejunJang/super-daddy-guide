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

            // 1. 수정된 refineBatchText 호출 (List<RefinedResult> 반환)
            List<RefinedResult> refinedResults = refineBatchText(batchTexts);

            // 2. 결과 매핑
            for (int j = 0; j < batchDocs.size(); j++) {
                Document originalDoc = batchDocs.get(j);
                RefinedResult result = (j < refinedResults.size()) ? refinedResults.get(j) : null;

                String text;
                var metadata = originalDoc.getMetadata(); // 기존 page_number 등 유지

                if (result != null && result.refined_text() != null && !result.refined_text().isBlank()) {
                    // 성공 케이스: 정제된 텍스트와 메타데이터 주입
                    text = result.refined_text();
                    metadata.put("section_title", result.section_title());
                    metadata.put("keywords", result.keywords()); // List<String> 형태로 저장
                } else {
                    // 실패 케이스 (Fallback): 원본 텍스트 공백 제거 후 기본값 설정
                    logger.warn("Batch result mapping failed for index {}. Using fallback.", j);
                    text = originalDoc.getText().replaceAll("\\s+", " ").trim();
                    metadata.put("section_title", "");
                    metadata.put("keywords", List.of());
                }

                cleanedDocuments.add(new Document(text, metadata));
            }

            // Rate Limit 준수 (Gemini API 안정성을 위해 배치 간 휴식)
            Thread.sleep(1000);
        }
        return cleanedDocuments;
    }

    private List<RefinedResult> refineBatchText(List<String> originalTexts) {
        String systemPrompt = """
                당신은 RAG(Retrieval-Augmented Generation) 시스템의 고성능 검색 품질을 보장하는 데이터 가공 전문가입니다.\s
                제공된 텍스트는 PDF에서 추출되어 레이아웃이 깨지거나 공백이 많습니다. 이를 검색 엔진이 가장 선호하는 형태로 재구성하세요.
                
                [핵심 작업 지시]
                1. 텍스트 정제 (refined_text):
                   - PDF 2단 구성으로 인해 가로로 섞인 문장들을 문맥에 맞게 완벽히 복구하세요.
                   - 단어 사이의 불필요한 다중 공백과 의미 없는 줄바꿈(\\n)을 모두 제거하고 한 줄의 자연스러운 문단으로 만드세요.
                   - '...했습 니다'와 같이 잘린 단어들을 결합하세요.
                
                2. 섹션 제목 추출 (section_title):
                   - '기본 섹션', '내용 요약' 같은 모호한 표현은 절대 금지합니다.
                   - 해당 페이지를 읽지 않아도 내용을 알 수 있도록 매우 구체적인 제목을 뽑으세요. (예: "6개월 아기 이유식 시작 시기 및 주의사항")
                   - 만약 명확한 제목이 없다면, 텍스트의 첫 번째 핵심 문장을 요약하여 제목으로 만드세요.
                
                3. 검색 키워드 추출 (keywords):
                   - 사용자가 이 내용을 찾기 위해 검색창에 입력할 법한 '질문형 키워드'와 '핵심 명사'를 섞어서 5개 추출하세요.
                   - 예: ["아기 걸음마 시기", "걸음마 훈련법", "아기 엉덩방아", "돌아기 발달", "걸음마 보조기"]
                
                [데이터 보존 원칙]
                - 원문의 수치(g, ml, 개월 수), 고유 명사, 전문 용어는 절대로 생략하거나 수정하지 말고 그대로 유지하세요.
                
                [출력 규칙 - 필독]
                - 오직 순수한 JSON 배열 형식으로만 응답하세요. (Markdown 블록 ```json 사용 금지)
                - 입력된 배열의 개수와 출력되는 배열의 개수가 반드시 일치해야 합니다.
                
                [응답 포맷]
                [
                  {
                    "refined_text": "정제된 문장",
                    "section_title": "구체적인 제목",
                    "keywords": ["키워드1", "키워드2", "키워드3", "키워드4", "키워드5"]
                  }
                ]
        """;

        try {
            String jsonInput = objectMapper.writeValueAsString(originalTexts);
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(jsonInput)
                    .call()
                    .content();

            String cleanJson = response.replaceAll("```json", "").replaceAll("```", "").trim();
            // List<RefinedResult> 형태로 파싱
            return objectMapper.readValue(cleanJson, new TypeReference<List<RefinedResult>>() {});
        } catch (Exception e) {
            logger.info("Gemini refinement failed: "+e.getMessage());
            logger.error("Gemini refinement failed", e);
            return originalTexts.stream().map(t -> new RefinedResult(t, "알 수 없는 섹션", List.of())).toList();
        }
    }

    private List<Document> splitAndEnrichDocuments(List<Document> cleanedDocuments, String fileName) {
        logger.info("Splitting and Optimizing for text-embedding-004...");
        // 청크 크기를 약간 키워 문맥 유지력을 높임 (Overlap은 유지)
        var tokenTextSplitter = new TokenTextSplitter(500, 150, 10, 5000, true);
        List<Document> processed = new ArrayList<>();

        for (Document doc : cleanedDocuments) {
            String sectionTitle = (String) doc.getMetadata().getOrDefault("section_title", "");
            List<String> keywordList = (List<String>) doc.getMetadata().getOrDefault("keywords", List.of());
            String keywordStr = String.join(", ", keywordList);

            List<Document> chunks = tokenTextSplitter.split(doc);

            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);

                // [개선 포인트 1] 불필요한 라벨("[주제:]", "[키워드:]") 제거
                // 임베딩 모델이 본연의 의미에 집중할 수 있도록 자연어 형태로 구성합니다.
                String optimizedText = String.format("""
                %s
                핵심 키워드: %s
                본문: %s
                """, sectionTitle, keywordStr, chunk.getText()).trim();

                // [개선 포인트 2] 메타데이터는 검색 필터링용으로 별도 저장 (벡터 연산에는 포함 안 됨)
                chunk.getMetadata().put("source", fileName);
                chunk.getMetadata().put("section_title", sectionTitle);
                chunk.getMetadata().put("keywords", keywordList); // 필터링을 위해 리스트 형태로 유지
                chunk.getMetadata().put("chunk_index", i);

                processed.add(new Document(optimizedText, chunk.getMetadata()));
            }
        }
        return processed;
    }

    private void ingestToVectorStore(List<Document> documents) throws InterruptedException {
        logger.info("Ingesting {} documents into Vector Store...", documents.size());
        int batchSize = 5;
        long delayMillis = 5000;

        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            vectorStore.accept(documents.subList(i, end));
            
            if (end < documents.size()) {
                Thread.sleep(delayMillis);
            }
        }
    }

    public record RefinedResult(
            String refined_text,
            String section_title,
            List<String> keywords
    ) {}
}
