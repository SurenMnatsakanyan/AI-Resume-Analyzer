package org.example;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
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

        PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(BUCKET_NAME)
            .key(key)
            .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(fileMap.get("file")));

        String response = mapper.writeValueAsString(Map.of(
            "statusCode", 200,
            "body", "Resume uploaded to S3 at key: " + key
        ));
        output.write(response.getBytes(StandardCharsets.UTF_8));
    }

    private void sendErrorResponse(OutputStream output, String message) throws IOException {
        String error = mapper.writeValueAsString(Map.of(
            "statusCode", 400,
            "body", message
        ));
        output.write(error.getBytes(StandardCharsets.UTF_8));
    }

}