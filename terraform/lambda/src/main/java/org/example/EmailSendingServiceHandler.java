package org.example;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

public class EmailSendingServiceHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String FROM_EMAIL = "resumeai.notifications@gmail.com";
    private static final SesClient sesClient = SesClient.create();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context)
    {
        try {
            final String message = (String) input.get("message");
            if (message.equals("Cached result already exists, skipping.")) {
                return Map.of("message", "success");
            }

            final String emailAddress = (String) input.get("email");
            context.getLogger().log("Staring sending a message to " + emailAddress);
            SendEmailRequest emailRequest = SendEmailRequest.builder()
                .destination(Destination.builder().toAddresses(emailAddress).build())
                .message(
                    Message.builder()
                        .subject(Content.builder().data("Your Resume Analysis Results").build())
                        .body(Body.builder().text(Content.builder().data(message).build()).build())
                        .build()
                )
                .source(FROM_EMAIL).build();
            sesClient.sendEmail(emailRequest);
            context.getLogger().log("SES email sent to user: " + emailAddress);
            return Map.of("message", "success");
        } catch (Exception e) {
            context.getLogger().log("Error in ResumeAnalyzerHandler: " + e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
}
