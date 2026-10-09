provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = local.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
