# Production. Fill in every REPLACE value before the first plan; domain, certificate and admin email fail
# variable validation until then. Recommended: a separate AWS account from staging (README, "Adding prod").

environment        = "prod"
aws_region         = "ap-south-1"
availability_zones = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
vpc_cidr           = "10.30.0.0/16"
one_nat_per_az     = true

domain_name         = "REPLACE.example.com"       # e.g. app.<your-domain>
acm_certificate_arn = "REPLACE_WITH_ACM_CERT_ARN" # issued certificate for domain_name, in ap-south-1

# Git SHA of the images pushed to ECR (the deploy pipeline sets the running image after the first bring-up).
api_image_tag = "REPLACE_WITH_GIT_SHA"
web_image_tag = "REPLACE_WITH_GIT_SHA"

# Two tasks of each in different AZs from the start, so one can fail or roll without an outage. Sizes and
# maximums are starting points; set them from the load-test report (docs/runbooks/load-testing.md).
api_cpu           = 1024
api_memory        = 2048
api_desired_count = 2
api_min_count     = 2
api_max_count     = 6

web_cpu           = 512
web_memory        = 1024
web_desired_count = 2
web_min_count     = 2
web_max_count     = 4

demo_enabled         = false
platform_admin_email = "REPLACE_WITH_ADMIN_EMAIL"

payments_gateway   = "sandbox" # "razorpay" once the live keys are in akshara/prod/payments-razorpay
email_enabled      = false     # true once the DKIM records are in DNS and SES has production access
email_from_address = null      # defaults to no-reply@<domain_name>

db_engine_version        = "17"
db_instance_class        = "db.m7g.large"
db_multi_az              = true
db_allocated_storage     = 100
db_max_allocated_storage = 1000
db_backup_retention_days = 30

backup_replica_region         = "ap-south-2" # Hyderabad: disaster-recovery copy of the backups
backup_replica_retention_days = 7

log_retention_days      = 90
flow_log_retention_days = 30

waf_rate_limit       = 2000
waf_login_rate_limit = 100
waf_logging_enabled  = true

alarm_emails              = ["REPLACE_WITH_ONCALL_EMAIL"]
alarm_latency_p95_seconds = 1.5
uptime_check_enabled      = true

# Own account: the account-wide GitHub OIDC provider is created here too.
create_github_oidc_provider = true
github_repository           = "rapolusai/School"
