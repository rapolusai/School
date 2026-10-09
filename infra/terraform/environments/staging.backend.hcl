# Usage: terraform init -backend-config=environments/staging.backend.hcl
# The bucket is created once by hand before the first init (README, "One-time prerequisites").
# Replace <account-id> with the 12-digit AWS account ID; S3 bucket names are global.
bucket       = "akshara-terraform-state-<account-id>"
key          = "akshara/staging/terraform.tfstate"
region       = "ap-south-1"
use_lockfile = true
encrypt      = true
