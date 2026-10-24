resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.database[*].id
}

resource "aws_db_parameter_group" "postgres" {
  name_prefix = "${local.name}-pg${split(".", var.db_engine_version)[0]}-"
  family      = "postgres${split(".", var.db_engine_version)[0]}"
  description = "${local.name} PostgreSQL settings"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  # A major-version upgrade needs a new group before the old one can be dropped.
  lifecycle {
    create_before_destroy = true
  }
}

# Pre-created so RDS log exports get a retention period instead of "never expire".
resource "aws_cloudwatch_log_group" "rds" {
  for_each = toset(["postgresql", "upgrade"])

  name              = "/aws/rds/instance/${local.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_db_instance" "main" {
  identifier = local.name

  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class
  multi_az       = var.db_multi_az

  db_name  = local.db_name
  username = "akshara_master"
  # RDS generates the master password and keeps it in its own Secrets Manager secret.
  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.secrets.arn

  storage_type          = "gp3"
  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.rds.arn

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.postgres.name

  # Times are UTC: backups 01:30-02:30 IST daily, maintenance Monday 03:00-04:00 IST.
  backup_retention_period    = var.db_backup_retention_days
  backup_window              = "20:00-21:00"
  maintenance_window         = "sun:21:30-sun:22:30"
  copy_tags_to_snapshot      = true
  auto_minor_version_upgrade = true

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name}-final"
  # Keep the automated backups for their retention period even if the instance is deleted by mistake.
  delete_automated_backups = false

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.rds.arn
  performance_insights_retention_period = 7
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.rds_monitoring.arn
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]

  depends_on = [aws_cloudwatch_log_group.rds]
}

# --- Copy of the backups in a second region (disaster recovery) --------------
# Point-in-time restore is then possible in backup_replica_region if ap-south-1 is unavailable
# (docs/runbooks/restore-database.md). The copy is encrypted with a key that lives in that region.

resource "aws_kms_key" "backup_replica" {
  count    = var.backup_replica_region == null ? 0 : 1
  provider = aws.backup

  description             = "${local.name} copy of the RDS automated backups"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_db_instance_automated_backups_replication" "main" {
  count    = var.backup_replica_region == null ? 0 : 1
  provider = aws.backup

  source_db_instance_arn = aws_db_instance.main.arn
  kms_key_id             = aws_kms_key.backup_replica[0].arn
  retention_period       = var.backup_replica_retention_days
}

# --- Enhanced monitoring ----------------------------------------------------

data "aws_iam_policy_document" "rds_monitoring_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "rds_monitoring" {
  name               = "${local.name}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.rds_monitoring_trust.json
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}
