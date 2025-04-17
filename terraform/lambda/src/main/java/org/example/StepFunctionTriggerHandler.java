package org.example;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

public class StepFunctionTriggerHandler implements RequestHandler<S3Event, String> {

    private static final SfnClient sfnClient = SfnClient.create();
    private static final String STATE_MACHINE_ARN = System.getenv("STATE_MACHINE_ARN");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        try {
            String bucket = s3Event.getRecords().get(0).getS3().getBucket().getName();
            String key = s3Event.getRecords().get(0).getS3().getObject().getKey();

            Map<String, String> inputMap = new HashMap<>();
            inputMap.put("bucket", bucket);
            inputMap.put("key", key);
            String inputJson = objectMapper.writeValueAsString(inputMap);

            StartExecutionRequest request = StartExecutionRequest.builder()
                .stateMachineArn(STATE_MACHINE_ARN)
                .input(inputJson)
                .build();

            StartExecutionResponse response = sfnClient.startExecution(request);

            return "Step function execution started: " + response.executionArn();
        } catch (Exception e) {
            context.getLogger().log("Error starting Step Function: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
