package org.example;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.RequestContext;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import redis.clients.jedis.Jedis;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class FileServiceHandler implements RequestStreamHandler
{

    private static final ObjectMapper mapper  = new ObjectMapper();
    private static final String BUCKET_NAME = System.getenv("BUCKET_NAME");
    private static final S3Client s3Client = S3Client
        .builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
        .build();
    private static final String REDIS_HOST = System.getenv("REDIS_HOST");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv("REDIS_PORT"));

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        context.getLogger().log("Lambda triggered.\n");
        Map<String, Object> event = mapper.readValue(input, Map.class);
        String body = (String) event.get("body");
        boolean isBase64Encoded = (boolean) event.getOrDefault("isBase64Encoded", false);
        Map<String, String> headers = (Map<String, String>) event.get("headers");

        if (!isBase64Encoded) {
            sendErrorResponse(output, "Expected base64-encoded body.");
            return;
        }

        byte[] decoded = Base64.getDecoder().decode(body);
        InputStream multipartStream = new ByteArrayInputStream(decoded);

        String contentType = headers.getOrDefault("content-type", headers.get("Content-Type"));
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            sendErrorResponse(output, "Content-Type must be multipart/form-data");
            return;
        }
        context.getLogger().log("Parsing multipart form.\n");

        DiskFileItemFactory factory = new DiskFileItemFactory();
        ServletFileUpload upload = new ServletFileUpload(factory);

        Map<String, String> formFields = new HashMap<>();
        Map<String, byte[]> fileMap = new HashMap<>();

        try {
            List<FileItem> items = upload.parseRequest(new RequestContext() {
                @Override public String getCharacterEncoding() { return "UTF-8"; }
                @Override public String getContentType() { return contentType; }
                @Override public int getContentLength() { return decoded.length; }
                @Override public InputStream getInputStream() { return multipartStream; }
            });

            for (FileItem item : items) {
                if (item.isFormField()) {
                    formFields.put(item.getFieldName(), item.getString());
                } else {
                    fileMap.put(item.getFieldName(), item.get());
                }
            }
        } catch (Exception e) {
            sendErrorResponse(output, "Failed to parse multipart form: " + e.getMessage());
            return;
        }

        if (!fileMap.containsKey("file")) {
            sendErrorResponse(output, "Missing resume file upload");
            return;
        }

        String email = formFields.getOrDefault("email", "unknown@example.com");
        String position = formFields.getOrDefault("position", "unknown-position");
        String firstName = formFields.getOrDefault("firstName", "unknown");
        String lastName = formFields.getOrDefault("lastName", "user");
        String emailHash = Base64.getUrlEncoder().encodeToString(email.getBytes(StandardCharsets.UTF_8));
        String positionSlug = position.toLowerCase().replaceAll("\\s+", "_");
        String nameSlug = (firstName + "_" + lastName).toLowerCase().replaceAll("\s+", "_");
        String key = emailHash + "/" + nameSlug + "/resume-" + positionSlug + ".pdf";
        context.getLogger().log("Email encoded is " + emailHash);

        byte[] fileBytes = fileMap.get("file");
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            context.getLogger().log("Hashing error: " + e.getMessage() + "\n");
            sendErrorResponse(output,  "Failed to initialize hash algorithm");
        }

        byte[] hashed = digest.digest(fileBytes);
        String resumeHash = Base64.getUrlEncoder().encodeToString(hashed);

        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            String redisKey = "resume:" + resumeHash + "position:" + positionSlug;
            context.getLogger().log("Checking Redis for key: " + redisKey + "\n");
            context.getLogger().log(positionSlug);
            if (jedis.exists(redisKey)) {
                context.getLogger().log("Cache hit. Returning cached result.\n");
                String cached = jedis.get(redisKey);
                String jsonBody = mapper.writeValueAsString(Map.of(
                    "message", "Duplicate resume. Cached result: " + cached
                ));
                String response = mapper.writeValueAsString(Map.of(
                    "statusCode", 200,
                    "headers", Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Methods", "POST, OPTIONS",
                        "Access-Control-Allow-Headers", "Content-Type"
                    ),
                    "body",jsonBody
                ));
                context.getLogger().log("The response is" + response);
                output.write(response.getBytes(StandardCharsets.UTF_8));
                return;
            }
        }  catch (Exception e) {
            context.getLogger().log("Redis connection error: " + e.getMessage() + "\n");
            sendErrorResponse(output, "Error connecting to Redis.");
            return;
        }

        context.getLogger().log("Uploading to S3: " + key + "\n");

        PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(BUCKET_NAME)
            .key(key)
            .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(fileMap.get("file")));
        context.getLogger().log("Upload complete.\n");
        headers = Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Methods", "POST, OPTIONS",
            "Access-Control-Allow-Headers", "Content-Type"
        );
        String jsonBody = mapper.writeValueAsString(Map.of(
            "message", "Resume uploaded to S3 at key:" + key
        ));
        String response = mapper.writeValueAsString(Map.of(
            "statusCode", 200,
            "headers", headers,
            "body", jsonBody
        ));
        context.getLogger().log("The response is " + response);
        output.write(response.getBytes(StandardCharsets.UTF_8));
    }

    private void sendErrorResponse(OutputStream output, String message) throws IOException {
        String messageJson  = mapper.writeValueAsString(Map.of(
            "message", message
        ));
        Map<String, String> headers = Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Methods", "POST, OPTIONS",
            "Access-Control-Allow-Headers", "Content-Type"
        );
        String error = mapper.writeValueAsString(Map.of(
            "statusCode", 400,
            "headers", headers,
            "body", messageJson
        ));
        output.write(error.getBytes(StandardCharsets.UTF_8));
    }

}