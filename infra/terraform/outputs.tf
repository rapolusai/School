output "alb_dns_name" {
  description = "Point domain_name at this (CNAME, or Route 53 alias)."
  value       = aws_lb.main.dns_name
}

output "ecr_repository_urls" {
  description = "Push targets for the api and web images."
  value       = { for name, repo in aws_ecr_repository.app : name => repo.repository_url }
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service_names" {
  value = { for name, service in aws_ecs_service.app : name => service.name }
}

output "task_definition_families" {
  description = "Families the deploy pipeline registers new revisions of."
  value = {
    api         = aws_ecs_task_definition.api.family
    api_migrate = aws_ecs_task_definition.api_migrate.family
    web         = aws_ecs_task_definition.web.family
  }
}

output "migrate_task_family" {
  description = "Task definition family to `aws ecs run-task` before each API rollout."
  value       = aws_ecs_task_definition.api_migrate.family
}

output "private_subnet_ids" {
  description = "Subnets for `aws ecs run-task` of api-migrate (and for a CloudShell VPC environment)."
  value       = aws_subnet.private[*].id
}

output "api_security_group_id" {
  description = "Security group for `aws ecs run-task` of api-migrate; the only group the database accepts."
  value       = aws_security_group.api.id
}

output "migrate_network_configuration" {
  description = "Ready-made value for `aws ecs run-task --network-configuration`."
  value = jsonencode({
    awsvpcConfiguration = {
      subnets        = aws_subnet.private[*].id
      securityGroups = [aws_security_group.api.id]
      assignPublicIp = "DISABLED"
    }
  })
}

output "rds_endpoint" {
  description = "Database host name (port 5432)."
  value       = aws_db_instance.main.address
}

output "rds_master_secret_arn" {
  description = "RDS-managed secret holding the master user's credentials (needed once for bootstrap/db-roles.sql)."
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "secret_arns" {
  description = "ARNs of the application secrets (values are never output)."
  value       = { for name, secret in aws_secretsmanager_secret.app : name => secret.arn }
}

output "github_deploy_role_arn" {
  description = "Role for aws-actions/configure-aws-credentials in the deploy workflow."
  value       = aws_iam_role.github_deploy.arn
}

output "alarm_topic_arn" {
  description = "SNS topic the CloudWatch alarms publish to (subscribe an email or chat endpoint)."
  value       = aws_sns_topic.alarms.arn
}
