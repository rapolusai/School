# Usage: terraform init -backend-config=environments/prod.backend.hcl
# The bucket is created once by hand before the first init (README, "One-time prerequisites"), in the
# production account. Replace <account-id> with that 12-digit AWS account ID; S3 bucket names are global.
bucket       = "akshara-terraform-state-<account-id>"
key          = "akshara/prod/terraform.tfstate"
region       = "ap-south-1"
use_lockfile = true
encrypt      = true
