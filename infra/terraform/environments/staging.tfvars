# Staging. Fill in every REPLACE value before the first plan; domain, certificate and admin email
# fail variable validation until then.

environment        = "staging"
aws_region         = "ap-south-1"
availability_zones = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
vpc_cidr           = "10.20.0.0/16"
one_nat_per_az     = false

domain_name         = "REPLACE.example.com"       # e.g. staging.<your-domain>
acm_certificate_arn = "REPLACE_WITH_ACM_CERT_ARN" # issued certificate for domain_name, in ap-south-1

# Git SHA of the images pushed to ECR (see README, "First-time bring-up").
api_image_tag = "REPLACE_WITH_GIT_SHA"
web_image_tag = "REPLACE_WITH_GIT_SHA"

api_cpu           = 512
api_memory        = 1024
api_desired_count = 1
api_min_count     = 1
api_max_count     = 2

web_cpu           = 256
web_memory        = 512
web_desired_count = 1
web_min_count     = 1
web_max_count     = 2

demo_enabled         = true
platform_admin_email = "REPLACE_WITH_ADMIN_EMAIL"

db_engine_version        = "17"
db_instance_class        = "db.t4g.medium"
db_multi_az              = false
db_allocated_storage     = 20
db_max_allocated_storage = 100

log_retention_days      = 30
flow_log_retention_days = 14

waf_rate_limit       = 2000
waf_login_rate_limit = 100
waf_logging_enabled  = false

create_github_oidc_provider = true
github_repository           = "rapolusai/School"
