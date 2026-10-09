terraform {
  # 1.11+ for S3-native state locking (use_lockfile).
  required_version = ">= 1.11.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Partial configuration: bucket, key, region, use_lockfile and encrypt come from
  # environments/<env>.backend.hcl at `terraform init -backend-config=...`.
  backend "s3" {}
}
