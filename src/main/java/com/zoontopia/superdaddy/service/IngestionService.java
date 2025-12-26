package com.zoontopia.superdaddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.List;

@Service
public class IngestionService implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;

    @Value("classpath:parenting_guide.pdf")
    private Resource pdfResource;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) throws Exception {

        String fileName = pdfResource.getFilename(); // "parenting_guide.pdf"

        // 1. 메타데이터 필터 생성: "source" 필드가 파일명과 일치하는지 확인
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();
        Filter.Expression filter = filterExpressionBuilder.eq("source", fileName).build();

        // 2. 해당 필터를 적용해 검색 (데이터 존재 여부 확인)
        var searchRequest = SearchRequest.builder()
                .query("any") // 필터가 중요하므로 검색어는 무관
                .filterExpression(filter)
                .topK(1)
                .build();

        var existingDocs = vectorStore.similaritySearch(searchRequest);
        
        if (!existingDocs.isEmpty()) {
            logger.info("Vector store already contains data (found {} documents). Skipping ingestion.", existingDocs.size());
            return;
        }

        if (!pdfResource.exists()) {
            logger.warn("parentingGuild.pdf not found. Skipping ingestion.");
            return;
        }

        try {
            logger.info("파일 '{}' 로딩 시작 (Chunk Size: 800)...", fileName);
            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(pdfResource);
            // Chunk Size를 1000 -> 1500으로 증가시켜 문맥 범위 확대
            var tokenTextSplitter = new TokenTextSplitter(800, 200, 5, 100, true);

            // 3. 텍스트를 쪼개고 각 조각(Document)에 파일명 메타데이터 추가
            var rawDocuments = pdfReader.get();
            List<Document> cleanedDocuments = rawDocuments.stream()
                    .map(doc -> {
                        String cleaned = doc.getText().replaceAll("\\s+", " ").trim();
                        return new Document(cleaned, doc.getMetadata());
                    })
                    .toList();


            var documents = tokenTextSplitter.apply(cleanedDocuments);
            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                doc.getMetadata().put("source", fileName);
                doc.getMetadata().put("chunk_index", i); // 순서 정보 추가
            }

            // 4. 안전한 배치 로딩 (이전 답변의 Rate Limit 대응 로직 적용)
            processBatchWithDelay(documents);

            logger.info("파일 '{}'의 문서 {}개 로드 완료.", fileName, documents.size());
        } catch (Exception e) {
            logger.error("Error loading PDF: {}", e.getMessage());
        }
    }

    private void processBatchWithDelay(List<Document> allDocs) throws InterruptedException {
        int batchSize = 5;
        long delayMillis = 10000;

        for (int i = 0; i < allDocs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allDocs.size());
            vectorStore.accept(allDocs.subList(i, end));
            if (end < allDocs.size()) Thread.sleep(delayMillis);
        }
    }
}
