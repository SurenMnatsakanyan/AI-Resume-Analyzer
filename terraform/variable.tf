variable "openai_api_key" {
  description = "Your OpenAI API key for ResumeAnalyzer Lambda"
  type        = string
  sensitive   = true
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "List of private subnet IDs for Redis and Lambda"
}

variable "vpc_id" {
  type        = string
  description = "VPC ID where Redis and Lambdas are deployed"
}