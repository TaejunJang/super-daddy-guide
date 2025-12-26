package com.zoontopia.superdaddy.service;

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

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    public String chat(String message) {
        // 1. Retrieve similar documents
        List<Document> similarDocuments = vectorStore.similaritySearch(SearchRequest.builder().query(message).topK(3).build());
        
        String context = similarDocuments.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        // 2. Construct System Prompt
        String systemPromptText = """
                You are 'Super Daddy', a helpful and encouraging assistant for new fathers.
                Use the following context to answer the user's question.
                If the answer is not in the context, use your general knowledge but mention that it's general advice.
                Speak in a friendly, supportive tone (like a kind brother or friend).
                
                Context:
                {context}
                """;

        // 3. Call Gemini
        return chatClient.prompt()
                .system(s -> s.text(systemPromptText).param("context", context))
                .user(message)
                .call()
                .content();
    }
}
