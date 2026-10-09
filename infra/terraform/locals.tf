data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

locals {
  project = "akshara"
  name    = "${local.project}-${var.environment}"

  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition

  az_count  = length(var.availability_zones)
  nat_count = var.one_nat_per_az ? local.az_count : 1

  api_port = 8080
  web_port = 3000

  db_name       = "akshara"
  db_owner_user = "akshara_owner"
  db_app_user   = "akshara_app"
}
