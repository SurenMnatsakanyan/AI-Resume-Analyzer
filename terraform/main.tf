# Terraform Backend Module - Resume AI Processing (File Upload + Lambda + API Gateway)

# --------------------------------------------
# 1. Define AWS Provider
# --------------------------------------------
# This tells Terraform to use AWS and which region to deploy to.
provider "aws" {
  region = "us-east-1"  # You can change this to your preferred AWS region
}

# --------------------------------------------
# 2. S3 Bucket to Store Uploaded Resumes
# --------------------------------------------
# This creates an S3 bucket where resumes will be uploaded.
resource "aws_s3_bucket" "resume_files" {
  bucket = "resume-upload-storage-12345"  # Change to a unique bucket name
  force_destroy = true

  tags = {
    Name        = "ResumeFileStorage"
    Environment = "dev"
  }
}

# --------------------------------------------
# 3. IAM Role and Permissions for Lambda
# --------------------------------------------
# Lambda needs permissions to run and access S3.
resource "aws_iam_role" "lambda_exec" {
  name = "lambda_execution_role"

  # IAM Trust policy: defines who can assume this role
  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action    = "sts:AssumeRole",
        Effect    = "Allow",
        Principal = {
          Service = "lambda.amazonaws.com"  # Only AWS Lambda can assume this role
        }
      }
    ]
  })
}

# Attach AWS-managed policy to allow Lambda to access S3
resource "aws_iam_role_policy_attachment" "lambda_s3_access" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
}

# Attach AWS-managed policy to allow basic Lambda execution (logs, etc.)
resource "aws_iam_role_policy_attachment" "lambda_basic_exec" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# --------------------------------------------
# 4. Lambda Function (Stub for Resume Upload)
# --------------------------------------------
# This creates a Lambda function that handles file uploads.
resource "aws_lambda_function" "file_service" {
  depends_on = [aws_s3_bucket.resume_files]
  function_name = "file-service"
  timeout = 10          # Increase from 3s to 10s
  memory_size = 512     # Boost from 128 MB to 512 MB

  filename         = "lambda/target/lambda-1.0-SNAPSHOT.jar"  # Path to your compiled JAR
  source_code_hash = filebase64sha256("lambda/target/lambda-1.0-SNAPSHOT.jar")

  runtime = "java17"  # Java 17 runtime for AWS Lambda
  handler = "org.example.FileServiceHandler::handleRequest"

  role = aws_iam_role.lambda_exec.arn

  environment {
    variables = {
      BUCKET_NAME = aws_s3_bucket.resume_files.id  # Environment variable for the S3 bucket
    }
  }

  tags = {
    Name = "FileServiceLambda"
  }
}

# 5 trigger_stepFunction
resource "aws_iam_role" "lambda_trigger_exec" {
  name = "lambda_trigger_execution_role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action    = "sts:AssumeRole",
        Effect    = "Allow",
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_trigger_stepfunction_exec" {
  role       = aws_iam_role.lambda_trigger_exec.name
  policy_arn = "arn:aws:iam::aws:policy/AWSStepFunctionsFullAccess"
}

resource "aws_iam_role_policy_attachment" "lambda_trigger_basic_exec" {
  role       = aws_iam_role.lambda_trigger_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "trigger_stepfunction" {
  function_name = "trigger-stepfunction"
  timeout = 10          # Increase from 3s to 10s
  memory_size = 512
  filename         = "lambda/target/lambda-1.0-SNAPSHOT.jar"
  source_code_hash = filebase64sha256("lambda/target/lambda-1.0-SNAPSHOT.jar")
  runtime = "java17"
  handler = "org.example.StepFunctionTriggerHandler::handleRequest"
  role    = aws_iam_role.lambda_trigger_exec.arn

  environment {
    variables = {
      STATE_MACHINE_ARN = aws_sfn_state_machine.resume_processor.arn
    }
  }

  tags = {
    Name = "TriggerStepFunctionLambda"
  }
}

resource "aws_lambda_permission" "allow_s3_to_invoke_lambda" {
  statement_id  = "AllowS3InvokeLambda"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.trigger_stepfunction.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.resume_files.arn
}

resource "aws_s3_bucket_notification" "resume_upload_event" {
  bucket = aws_s3_bucket.resume_files.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.trigger_stepfunction.arn
    events              = ["s3:ObjectCreated:Put"]
    filter_suffix       = ".pdf"
  }

  depends_on = [aws_lambda_permission.allow_s3_to_invoke_lambda]
}

# 6. Lambda Function - ResumeAnalyzer (OpenAI instead of Textract)
# --------------------------------------------
resource "aws_lambda_function" "resume_analyzer" {
  function_name = "resume-analyzer"
  timeout       = 15
  memory_size   = 1024
  filename         = "lambda/target/lambda-1.0-SNAPSHOT.jar"
  source_code_hash = filebase64sha256("lambda/target/lambda-1.0-SNAPSHOT.jar")
  runtime          = "java17"
  handler          = "org.example.ResumeAnalyzerHandler::handleRequest"
  role             = aws_iam_role.lambda_exec.arn

  environment {
    variables = {
      OPENAI_API_KEY = var.openai_api_key
    }
  }

  tags = {
    Name = "ResumeAnalyzerLambda"
  }
}

# --------------------------------------------
# 7. Role and Polices for recommendation-scoring-service
# --------------------------------------------
resource "aws_iam_role" "processing_lambda_exec" {
  name = "processing_lambda_exec_role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect    = "Allow",
        Principal = { Service = "lambda.amazonaws.com" },
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "processing_lambda_logs" {
  role       = aws_iam_role.processing_lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "processing_lambda_dynamo" {
  role       = aws_iam_role.processing_lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess"
}

# --------------------------------------------
# 8. Lambda - Recommendation And Scoring Lambda
# --------------------------------------------
resource "aws_lambda_function" "recommendation-scoring-service" {
  function_name = "recommendation-scoring-service"
  timeout       = 10
  memory_size   = 512
  filename         = "lambda/target/lambda-1.0-SNAPSHOT.jar"
  source_code_hash = filebase64sha256("lambda/target/lambda-1.0-SNAPSHOT.jar")
  runtime          = "java17"
  handler          = "org.example.RecommendationScoringServiceHandler::handleRequest"
  role             = aws_iam_role.processing_lambda_exec.arn

  environment {
    variables = {
      DYNAMO_TABLE_NAME = aws_dynamodb_table.resume_scores.name
    }
  }

  tags = {
    Name = "RecommendationLambda"
  }
}

# --------------------------------------------
# 9. DynamoDB Table to store scores/recommendations
# --------------------------------------------
resource "aws_dynamodb_table" "resume_scores" {
  name           = "ResumeScores"
  billing_mode   = "PAY_PER_REQUEST"
  hash_key       = "resumeId"

  attribute {
    name = "resumeId"
    type = "S"
  }

  tags = {
    Name = "ResumeScoresTable"
  }
}

# --------------------------------------------
# 10. Update Step Function - calls ResumeAnalyzer → Scoring → Recommendation
# --------------------------------------------
resource "aws_iam_role" "stepfunction_exec" {
  name = "stepfunction_execution_role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Principal = {
          Service = "states.amazonaws.com"
        },
        Action = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy" "stepfunction_exec_policy" {
  name = "stepfunction_exec_policy"
  role = aws_iam_role.stepfunction_exec.id

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "logs:*",
          "lambda:InvokeFunction"
        ],
        Resource = "*"
      }
    ]
  })
}

resource "aws_sfn_state_machine" "resume_processor" {
  name     = "resume-processing-state-machine"
  role_arn = aws_iam_role.stepfunction_exec.arn

  definition = jsonencode({
    Comment = "Resume processing with OpenAI and scoring",
    StartAt = "ResumeAnalyzer",
    States = {
      ResumeAnalyzer = {
        Type     = "Task",
        Resource = aws_lambda_function.resume_analyzer.arn,
        Next     = "RecommendationAndScoringService"
      },
      RecommendationAndScoringService = {
        Type     = "Task",
        Resource = aws_lambda_function.recommendation-scoring-service.arn,
        End = true
      }
    }
  })
}


# --------------------------------------------
# 8. API Gateway Configuration (REST API)
# --------------------------------------------
# This creates a REST API with /upload POST endpoint linked to the Lambda.
resource "aws_api_gateway_rest_api" "resume_api" {
  name        = "ResumeProcessingAPI"
  description = "API for uploading resumes to S3 and triggering processing"
  binary_media_types = ["multipart/form-data"]
}

# Create /upload resource path
resource "aws_api_gateway_resource" "upload" {
  rest_api_id = aws_api_gateway_rest_api.resume_api.id
  parent_id   = aws_api_gateway_rest_api.resume_api.root_resource_id
  path_part   = "upload"
}

# Define the POST method on /upload (open for now)
resource "aws_api_gateway_method" "upload_post" {
  rest_api_id   = aws_api_gateway_rest_api.resume_api.id
  resource_id   = aws_api_gateway_resource.upload.id
  http_method   = "POST"
  authorization = "NONE"
}

# Connect the POST method to the Lambda function
resource "aws_api_gateway_integration" "lambda_integration" {
  rest_api_id             = aws_api_gateway_rest_api.resume_api.id
  resource_id             = aws_api_gateway_resource.upload.id
  http_method             = aws_api_gateway_method.upload_post.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"  # Enables full request forwarding to Lambda
  uri                     = aws_lambda_function.file_service.invoke_arn
}

# Allow API Gateway to call the Lambda
resource "aws_lambda_permission" "api_gw" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.file_service.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.resume_api.execution_arn}/*/*"
}

# Deploy the API to a stage (like 'dev')
resource "aws_api_gateway_deployment" "resume_api_deployment" {
  depends_on = [aws_api_gateway_integration.lambda_integration]
  rest_api_id = aws_api_gateway_rest_api.resume_api.id
}

resource "aws_api_gateway_stage" "dev_stage" {
  stage_name    = "dev"
  rest_api_id   = aws_api_gateway_rest_api.resume_api.id
  deployment_id = aws_api_gateway_deployment.resume_api_deployment.id
}
