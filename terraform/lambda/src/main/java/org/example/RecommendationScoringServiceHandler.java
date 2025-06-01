package org.example;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class RecommendationScoringServiceHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final DynamoDbClient dynamoDb = DynamoDbClient.create();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String TABLE_NAME = System.getenv("DYNAMO_TABLE_NAME");
    private static final String REDIS_HOST = System.getenv("REDIS_HOST");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv("REDIS_PORT"));

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        try {
            String encodedEmail = input.get("emailEncoded").toString();
            String email = new String(Base64.getUrlDecoder().decode(encodedEmail), StandardCharsets.UTF_8);
            context.getLogger().log("Your email is " + email);
            String resumeHash = input.get("resumeHash").toString();
            int score = (int) input.get("score");
            List<String> recommendations = (List<String>) input.get("recommendations");

            String redisKey = "resume:" + resumeHash;
            String message = null;
            try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
                if (jedis.exists(redisKey)) {
                    context.getLogger().log("Resume already processed, skipping DB write and SES.\n");
                    return Map.of("message", "Cached result already exists, skipping.");
                }

                // Save to DynamoDB
                Map<String, AttributeValue> item = new HashMap<>();
                item.put("resumeId", AttributeValue.fromS(resumeHash));
                item.put("score", AttributeValue.fromN(String.valueOf(score)));
                item.put("recommendations", AttributeValue.fromL(
                    recommendations.stream().map(AttributeValue::fromS).toList()
                ));
                item.put("analysisDate", AttributeValue.fromS(Instant.now().toString()));

                context.getLogger().log("Putting the item to Db");

                dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .build());

                context.getLogger().log("Finished Putting the item to Db");
                context.getLogger().log("Caching the item");
                Map<String, Object> cachedValue = Map.of("score", score, "recommendations", recommendations);
                jedis.setex(redisKey, 3600,  mapper.writeValueAsString(cachedValue));  // Cache for 1 hour
                context.getLogger().log("Cached resume hash in Redis: " + redisKey);
                context.getLogger().log("Saved to Redis: " + jedis.get(redisKey));
                message = String.format("""
                    Hello %s,

                    Your resume has been analyzed. Here are your results:

                    Score: %d

                    Recommendations:
                    %s

                    Best of luck!
                    """, email, score, String.join("\n- ", recommendations));
                context.getLogger().log("Finished caching the item");
                context.getLogger().log("Finished SES sending");
                context.getLogger().log("SES email sent to user: " + email);
            }

            return Map.of("status", "success", "email", email, "score", score, "message", message);
        } catch (Exception e) {
            context.getLogger().log("Error in RecommendationScoringHandler: " + e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

}
