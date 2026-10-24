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

# Route 53 health checks publish their metrics only in us-east-1, so the uptime alarm and its topic live there.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = local.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# Receives the copy of the database backups. Configured even when the copy is off (it then points at the main
# region and nothing uses it), because a provider block cannot be made conditional.
provider "aws" {
  alias  = "backup"
  region = coalesce(var.backup_replica_region, var.aws_region)

  default_tags {
    tags = {
      Project     = local.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
