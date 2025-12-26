package com.zoontopia.superdaddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class IngestionService implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;

    @Value("file:parentingGuild.pdf")
    private Resource pdfResource;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) throws Exception {
        // Check if the vector store already contains data to avoid duplicate ingestion
        // We assume that if we find at least one document, the store is populated.
        var searchRequest = org.springframework.ai.vectorstore.SearchRequest.builder().query("parenting").topK(1).build();
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
            logger.info("Loading parentingGuild.pdf into Vector Store...");
            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(pdfResource);
            
            // Configure TokenTextSplitter to respect API token limits.
            // text-embedding-004 has a limit of 2048 tokens. 
            // We use 1000 to be safe and allow for metadata/overhead.
            var tokenTextSplitter = new TokenTextSplitter(1000, 400, 200, 100, true);
            
            var documents = tokenTextSplitter.apply(pdfReader.get());
            
            vectorStore.accept(documents);
            logger.info("Successfully loaded {} documents into Vector Store.", documents.size());
        } catch (Exception e) {
            logger.error("Error loading PDF: {}", e.getMessage());
        }
    }
}
