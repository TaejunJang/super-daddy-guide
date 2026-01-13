package com.zoontopia.superdaddy.service;

import com.zoontopia.superdaddy.domain.entity.ChunkNode;
import com.zoontopia.superdaddy.domain.entity.EntityNode;
import com.zoontopia.superdaddy.repository.ChunkRepository;
import com.zoontopia.superdaddy.repository.EntityRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final ChunkRepository chunkRepository;
    private final EntityRepository entityRepository;
    private final EmbeddingModel embeddingModel;
    private final EntityExtractor entityExtractor;
    private final TextCleaner textCleaner;

    // DB 조회를 줄이기 위한 엔티티 캐시
    private final Map<String, EntityNode> entityCache = new ConcurrentHashMap<>();

    // 장시간 실행되는 트랜잭션을 피하기 위해 @Transactional 제거
    public String ingestParentingGuide() {
        log.info("Starting ingestion process...");

        try {
            // 1. 기존 엔티티를 캐시에 미리 로드 (DB 크기에 따라 선택 사항)
            // 초기 시작이나 작은 DB에는 적합. 대규모 DB의 경우 LRU 캐시나 타겟 조회를 사용.
            entityRepository.findAll().forEach(e -> entityCache.put(e.getName(), e));

            // 2. LangChain4j를 사용하여 PDF 읽기
            ClassPathResource pdfResource = new ClassPathResource("parenting_guide.pdf");
            Document document = new ApacheTikaDocumentParser().parse(pdfResource.getInputStream());

            // 3. 청크로 분할 (재귀적 문자 분할)
            // 청크당 약 1000자, 200자 중복
            List<TextSegment> splitDocs = DocumentSplitters.recursive(1000, 200).split(document);

            log.info("PDF split into {} chunks.", splitDocs.size());

            int processedCount = 0;
            for (TextSegment segment : splitDocs) {
                processChunk(segment.text());
                processedCount++;

                // API Rate Limit (503 Service Unavailable) 방지를 위한 지연
                try {
                    Thread.sleep(500); // 안전하게 0.5초 대기
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                if (processedCount % 10 == 0) {
                    log.info("Processed {}/{} chunks", processedCount, splitDocs.size());
                }
            }

            return "Ingestion completed. Processed " + processedCount + " chunks.";

        } catch (Exception e) {
            log.error("Ingestion failed", e);
            return "Ingestion failed: " + e.getMessage();
        }
    }

    @Transactional // 청크 단위 트랜잭션 (배치 단위일 수도 있음)
    protected void processChunk(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return;
        }

        // 4. 텍스트 정제 (Text Cleaning) - 재시도 로직 포함
        String text = rawText;
        int maxRetries = 3;
        boolean cleaned = false;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                text = textCleaner.cleanText(rawText);
                cleaned = true;
                break;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    log.warn("Failed to clean text after {} attempts. Using raw text. Error: {}", maxRetries, e.getMessage());
                } else {
                    log.warn("Text cleaning failed (attempt {}/{}), retrying in 1s...", attempt, maxRetries);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (cleaned) {
            // 정제 후 잠시 대기 (Rate Limit 방지)
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 5. 임베딩 생성
        Response<Embedding> embeddingResponse = embeddingModel.embed(text);
        List<Float> embeddingVector = embeddingResponse.content().vectorAsList();

        // 6. 청크 노드 생성
        ChunkNode chunkNode = new ChunkNode(text, embeddingVector);

        // 7. 엔티티 추출 (재시도 로직 포함)
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                List<String> extractedNames = entityExtractor.extractEntities(text);
                Thread.sleep(1000);
                for (String name : extractedNames) {
                    String cleanName = name.trim();
                    if (!cleanName.isEmpty()) {
                        // 캐시에서 가져오거나 새로 생성
                        EntityNode entityNode = entityCache.computeIfAbsent(cleanName, k -> {
                             return entityRepository.findByName(k).orElseGet(() -> entityRepository.save(new EntityNode(k)));
                        });
                        
                        chunkNode.addMention(entityNode);
                    }
                }
                break; // 성공 시 루프 탈출
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    log.warn("Failed to extract entities for chunk after {} attempts: {}", maxRetries, e.getMessage());
                } else {
                    log.warn("Entity extraction failed (attempt {}/{}), retrying in 1s...", attempt, maxRetries);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        // 8. 청크 노드 저장
        chunkRepository.save(chunkNode);
    }
}
