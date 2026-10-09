# Two customer-managed keys so that roles able to decrypt application secrets cannot decrypt the
# database volume (and the reverse). Both use the default key policy, which delegates access to IAM.

resource "aws_kms_key" "rds" {
  description             = "${local.name} RDS storage and Performance Insights"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "rds" {
  name          = "alias/${local.name}-rds"
  target_key_id = aws_kms_key.rds.key_id
}

resource "aws_kms_key" "secrets" {
  description             = "${local.name} Secrets Manager secrets"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${local.name}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}
