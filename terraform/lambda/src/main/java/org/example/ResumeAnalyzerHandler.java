package org.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import okhttp3.*;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class ResumeAnalyzerHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final S3Client s3Client = S3Client.create();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String OPENAI_URL = "https://api.openai.com/v1/responses";

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        try {
            String bucket = (String) input.get("bucket");
            String encodedKey = (String) input.get("key");
            String key = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8.name());
            context.getLogger().log("Analyzing resume for key: " + key + " bucket: " + bucket);

            int startIndex = key.indexOf("/resume-") + 8;
            int lastIndex = key.indexOf(".pdf");
            String position = key.substring(startIndex, lastIndex).replace("_", " ");

            context.getLogger().log("Analyzing resume for position: " + position + "\n");

            // Download the PDF resume
            byte[] fileBytes = downloadFileFromS3(bucket, key);
            String base64File = Base64.getEncoder().encodeToString(fileBytes);

            context.getLogger().log("PDF size: " + fileBytes.length + " bytes");
            context.getLogger().log("Base64 length: " + base64File.length());

            // Build the prompt
            String prompt = """
                You are an AI Resume Analyzer.

                Your task is to analyze the uploaded resume and evaluate it for the position: "%s".

                Please provide the following structured JSON output:

                {
                  "score": <integer from 0 to 100>,
                  "recommendations": [
                    "short actionable recommendation 1",
                    "short actionable recommendation 2",
                    "short actionable recommendation n"
                  ],
                  "analysisDate": "<ISO 8601 date>"
                }

                Scoring Criteria:
                - Relevance of experience to the job title
                - Technical skills matching the position
                - Clarity and conciseness of resume
                - Formatting and structure
                - Soft skills or leadership (if evident)

                Be concise. Focus on value for hiring managers.
                """.formatted(position);

            // Build JSON payload for OpenAI
            String[] keys =  key.split("/");
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", "gpt-4.1");

            context.getLogger().log("Filename: " + keys[keys.length - 1]);

            ArrayNode inputArray = payload.putArray("input");

            ObjectNode userEntry = mapper.createObjectNode();
            userEntry.put("role", "user");

            ArrayNode contentArray = userEntry.putArray("content");

            ObjectNode fileNode = mapper.createObjectNode();
            fileNode.put("type", "input_file");
            fileNode.put("filename", keys[keys.length - 1]);
            fileNode.put("file_data", "data:application/pdf;base64," + base64File);
            contentArray.add(fileNode);

            ObjectNode textNode = mapper.createObjectNode();
            textNode.put("type", "input_text");
            textNode.put("text", prompt);
            contentArray.add(textNode);

            inputArray.add(userEntry);

            // Call OpenAI
            String response = callOpenAI(payload.toString());


            JsonNode fullResponse = mapper.readTree(response);
            String rawText = fullResponse
                .path("output").get(0)
                .path("content").get(0)
                .path("text").asText();

            Map<String, Object> parsedResult = mapper.readValue(rawText, new TypeReference<>() {});
            parsedResult.put("emailHash", key.split("/")[0]);

            context.getLogger().log("The score result is " + parsedResult.get("score"));
            context.getLogger().log("The recommendations result is " + parsedResult.get("recommendations"));

            return parsedResult;
        } catch (Exception e) {
            context.getLogger().log("Error in ResumeAnalyzerHandler: " + e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    private byte[] downloadFileFromS3(String bucket, String key) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();

        try (ResponseInputStream<?> s3Stream = s3Client.getObject(request)) {
            return s3Stream.readAllBytes();
        }
    }

    private String callOpenAI(String requestBody) throws IOException {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
            .url(OPENAI_URL)
            .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("OpenAI API error: " + response);
            return response.body().string();
        }
    }
}
