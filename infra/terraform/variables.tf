# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------

variable "environment" {
  description = "Environment name, used in resource names, tags and secret paths (e.g. staging, prod)."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,11}$", var.environment))
    error_message = "environment must be 2-12 lowercase letters/digits, starting with a letter."
  }
}

variable "aws_region" {
  description = "AWS region for every resource."
  type        = string
  default     = "ap-south-1"
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

variable "availability_zones" {
  description = "Availability zones to spread subnets over (2 or 3; the subnet layout in network.tf assumes at most 3)."
  type        = list(string)
  default     = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]

  validation {
    condition     = length(var.availability_zones) >= 2 && length(var.availability_zones) <= 3
    error_message = "Use 2 or 3 availability zones."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR block. A /16 is expected; subnets are carved from it in network.tf."
  type        = string
  default     = "10.20.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && try(tonumber(split("/", var.vpc_cidr)[1]) <= 16, false)
    error_message = "vpc_cidr must be a valid IPv4 CIDR of /16 or larger."
  }
}

variable "one_nat_per_az" {
  description = "One NAT gateway per AZ (resilient, for prod) instead of a single shared one (cheaper, for staging)."
  type        = bool
  default     = false
}

variable "flow_log_retention_days" {
  description = "CloudWatch retention for VPC flow logs."
  type        = number
  default     = 30
}

variable "log_retention_days" {
  description = "CloudWatch retention for container, database and WAF logs."
  type        = number
  default     = 30
}

# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

variable "domain_name" {
  description = "Public host name of the app (e.g. staging.example.com). DNS is managed outside Terraform and points at the ALB."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.domain_name))
    error_message = "domain_name must be a lowercase host name such as staging.example.com."
  }
}

variable "acm_certificate_arn" {
  description = "ARN of an issued ACM certificate in aws_region that covers domain_name. Created outside Terraform."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:acm:[a-z0-9-]+:[0-9]{12}:certificate/[0-9a-f-]+$", var.acm_certificate_arn))
    error_message = "acm_certificate_arn must be an ACM certificate ARN."
  }
}

variable "waf_rate_limit" {
  description = "Requests allowed per client IP per 5 minutes across the whole site."
  type        = number
  default     = 2000
}

variable "waf_login_rate_limit" {
  description = "Requests allowed per client IP per 5 minutes to the login and signup endpoints."
  type        = number
  default     = 100
}

variable "waf_trusted_ips" {
  description = "IPv4 CIDRs (e.g. a load generator's \"203.0.113.10/32\") allowed past every WAF rule. Only for the length of a load test; keep it empty otherwise."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for c in var.waf_trusted_ips : can(cidrhost(c, 0)) && !startswith(c, "0.0.0.0")])
    error_message = "waf_trusted_ips must be IPv4 CIDRs and must not be 0.0.0.0/x."
  }
}

variable "waf_logging_enabled" {
  description = "Send WAF request logs (cookies and Authorization headers redacted) to CloudWatch Logs."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Containers
# ---------------------------------------------------------------------------

variable "api_image_tag" {
  description = "Image tag in the akshara-api repository used when Terraform registers the api and api-migrate task definitions."
  type        = string
}

variable "web_image_tag" {
  description = "Image tag in the akshara-web repository used when Terraform registers the web task definition."
  type        = string
}

variable "api_cpu" {
  description = "Fargate CPU units for one API task (1024 = 1 vCPU)."
  type        = number
  default     = 512
}

variable "api_memory" {
  description = "Fargate memory (MiB) for one API task."
  type        = number
  default     = 1024
}

variable "api_desired_count" {
  description = "API tasks when the service is first created; afterwards autoscaling owns the count."
  type        = number
  default     = 1
}

variable "api_min_count" {
  description = "Minimum API tasks kept by autoscaling."
  type        = number
  default     = 1
}

variable "api_max_count" {
  description = "Maximum API tasks autoscaling may run."
  type        = number
  default     = 3
}

variable "web_cpu" {
  description = "Fargate CPU units for one web task (1024 = 1 vCPU)."
  type        = number
  default     = 256
}

variable "web_memory" {
  description = "Fargate memory (MiB) for one web task."
  type        = number
  default     = 512
}

variable "web_desired_count" {
  description = "Web tasks when the service is first created; afterwards autoscaling owns the count."
  type        = number
  default     = 1
}

variable "web_min_count" {
  description = "Minimum web tasks kept by autoscaling."
  type        = number
  default     = 1
}

variable "web_max_count" {
  description = "Maximum web tasks autoscaling may run."
  type        = number
  default     = 3
}

variable "enable_execute_command" {
  description = "Allow ECS Exec (shell into running api/web containers). Adds the SSM channel permissions to the task roles."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Application settings
# ---------------------------------------------------------------------------

variable "demo_enabled" {
  description = "Seed and enable the demo schools (DEMO_ENABLED) and create the demo password secret."
  type        = bool
  default     = false
}

variable "platform_admin_email" {
  description = "Login email of the bootstrap platform administrator (stored in Secrets Manager with a generated password)."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.platform_admin_email))
    error_message = "platform_admin_email must be an email address."
  }
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

variable "db_engine_version" {
  description = "PostgreSQL version. A major version only (e.g. \"17\") lets RDS pick and auto-upgrade the minor version."
  type        = string
  default     = "17"
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.medium"
}

variable "db_multi_az" {
  description = "Run a synchronous standby in a second AZ (automatic failover). Doubles the instance cost."
  type        = bool
  default     = true
}

variable "db_allocated_storage" {
  description = "Initial gp3 storage in GiB."
  type        = number
  default     = 20
}

variable "db_max_allocated_storage" {
  description = "Upper limit in GiB for RDS storage autoscaling."
  type        = number
  default     = 100
}

variable "db_backup_retention_days" {
  description = "Days of automated backups (point-in-time restore window). RDS allows 1-35."
  type        = number
  default     = 14

  validation {
    condition     = var.db_backup_retention_days >= 1 && var.db_backup_retention_days <= 35
    error_message = "db_backup_retention_days must be between 1 and 35."
  }
}

variable "backup_replica_region" {
  description = "Second region that receives a copy of the automated backups (disaster recovery), e.g. ap-south-2 (Hyderabad). null turns the copy off."
  type        = string
  default     = null
}

variable "backup_replica_retention_days" {
  description = "Days the copied backups are kept in backup_replica_region."
  type        = number
  default     = 7
}

# ---------------------------------------------------------------------------
# Payments, email and file storage
# ---------------------------------------------------------------------------

variable "payments_gateway" {
  description = "Online fee payment gateway: \"sandbox\" (no real money) or \"razorpay\". Put the Razorpay keys in the akshara/<env>/payments-razorpay secret before switching (README)."
  type        = string
  default     = "sandbox"

  validation {
    condition     = contains(["sandbox", "razorpay"], var.payments_gateway)
    error_message = "payments_gateway must be sandbox or razorpay."
  }
}

variable "email_enabled" {
  description = "Send email through Amazon SES. Off, email stays simulated (recorded, never sent). Needs the SES domain verified (DKIM records) and SES out of its sandbox."
  type        = bool
  default     = false
}

variable "email_from_address" {
  description = "Sender address for email, in the verified SES domain (e.g. no-reply@example.com). null uses no-reply@<domain_name>."
  type        = string
  default     = null
}

variable "files_noncurrent_retention_days" {
  description = "Days an overwritten or deleted uploaded file is kept in the files bucket before S3 removes it."
  type        = number
  default     = 30
}

# ---------------------------------------------------------------------------
# Monitoring
# ---------------------------------------------------------------------------

variable "alarm_5xx_threshold" {
  description = "5xx responses per 5 minutes (from the ALB itself, and from targets) above which an alarm fires."
  type        = number
  default     = 10
}

variable "alarm_latency_p95_seconds" {
  description = "API p95 response time (seconds, over 5 minutes) above which an alarm fires."
  type        = number
  default     = 2
}

variable "alarm_emails" {
  description = "Email addresses subscribed to the alarm topics. Each gets a confirmation email from AWS that must be accepted."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for e in var.alarm_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", e))])
    error_message = "Every entry in alarm_emails must be an email address."
  }
}

variable "uptime_check_enabled" {
  description = "Route 53 health check of https://<domain_name>/login from several regions outside the VPC, with an alarm (in us-east-1, where Route 53 publishes its metrics)."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------
# CI/CD
# ---------------------------------------------------------------------------

variable "create_github_oidc_provider" {
  description = "Create the account-wide GitHub Actions OIDC provider. Set false if the account already has one (only one may exist)."
  type        = bool
  default     = true
}

variable "github_repository" {
  description = "GitHub owner/repo allowed to assume the deploy role. IAM compares it case-sensitively with the token, which carries GitHub's own spelling."
  type        = string
  default     = "rapolusai/School"
}
