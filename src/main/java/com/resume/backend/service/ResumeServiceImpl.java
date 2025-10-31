package com.resume.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
public class ResumeServiceImpl implements ResumeService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    public ResumeServiceImpl() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Override
    public Map<String, Object> generateResumeResponse(String userResumeDescription) throws IOException {

        String promptString = this.loadPromptFromFile("resume_prompt.txt");
        String promptContent = this.putValuesToTemplate(promptString, Map.of(
                "userDescription", userResumeDescription
        ));

        // Create Gemini API request
        Map<String, Object> requestBody = new HashMap<>();

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> part = new HashMap<>();
        part.put("text", promptContent);
        parts.add(part);
        content.put("parts", parts);
        contents.add(content);
        requestBody.put("contents", contents);

        // Add generation config
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.7);
        generationConfig.put("maxOutputTokens", 2048);
        requestBody.put("generationConfig", generationConfig);

        // Call Gemini API
        String fullUrl = geminiApiUrl + geminiApiKey;

        Map<String, Object> response = webClient.post()
                .uri(fullUrl)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(error -> {
                    System.err.println("Error calling Gemini API: " + error.getMessage());
                    return Mono.just(Map.of("error", error.getMessage()));
                })
                .block();

        // Extract text from Gemini response
        String responseText = extractTextFromGeminiResponse(response);

        // Parse the response
        Map<String, Object> parsedResponse = parseMultipleResponses(responseText);

        return parsedResponse;
    }

    private String extractTextFromGeminiResponse(Map<String, Object> response) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                Map<String, Object> content = (Map<String, Object>) candidate.get("content");
                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    return (String) parts.get(0).get("text");
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting text from Gemini response: " + e.getMessage());
        }
        return "";
    }

    String loadPromptFromFile(String filename) throws IOException {
        try {
            Path path = new ClassPathResource(filename).getFile().toPath();
            return Files.readString(path);
        } catch (Exception e) {
            // Return default prompt if file not found
            return "Based on this description: {{userDescription}}, generate a professional resume in JSON format with personalInfo, experience, education, skills, and projects sections.";
        }
    }

    String putValuesToTemplate(String template, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }

    public static Map<String, Object> parseMultipleResponses(String response) {
        Map<String, Object> jsonResponse = new HashMap<>();

        // Extract content inside <think> tags if present
        int thinkStart = response.indexOf("<think>");
        int thinkEnd = response.indexOf("</think>");
        if (thinkStart != -1 && thinkEnd != -1) {
            String thinkContent = response.substring(thinkStart + 7, thinkEnd).trim();
            jsonResponse.put("think", thinkContent);
        }

        // Extract JSON content
        int jsonStart = response.indexOf("```json");
        int jsonEnd = response.lastIndexOf("```");

        if (jsonStart != -1 && jsonEnd != -1 && jsonStart < jsonEnd) {
            String jsonContent = response.substring(jsonStart + 7, jsonEnd).trim();
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> dataContent = objectMapper.readValue(jsonContent, Map.class);
                jsonResponse.put("data", dataContent);
            } catch (Exception e) {
                // Try direct parsing if markdown format fails
                try {
                    ObjectMapper objectMapper = new ObjectMapper();
                    Map<String, Object> dataContent = objectMapper.readValue(response, Map.class);
                    jsonResponse.put("data", dataContent);
                } catch (Exception ex) {
                    jsonResponse.put("data", Map.of("response", response));
                }
            }
        } else {
            // Try direct JSON parsing
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> dataContent = objectMapper.readValue(response, Map.class);
                jsonResponse.put("data", dataContent);
            } catch (Exception e) {
                jsonResponse.put("data", Map.of("response", response));
            }
        }

        return jsonResponse;
    }
}