package org.example;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class RecommendationScoringServiceHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final DynamoDbClient dynamoDb = DynamoDbClient.create();
    private static final String TABLE_NAME = System.getenv("DYNAMO_TABLE_NAME");

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String resumeId = input.getOrDefault("emailHash", UUID.randomUUID().toString()).toString();
        List<String> recommendations = (List<String>) input.get("recommendations");
        int score = (int) input.get("score");

        context.getLogger().log("Saving score for: " + resumeId + "\n");

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("resumeId", AttributeValue.fromS(resumeId));
        item.put("score", AttributeValue.fromN(String.valueOf(score)));
        item.put("recommendations", AttributeValue.fromL(
            recommendations.stream().map(AttributeValue::fromS).toList()
        ));
        item.put("analysisDate", AttributeValue.fromS(Instant.now().toString()));

        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(TABLE_NAME)
            .item(item)
            .build());

        return input;
    }
}
