resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "ecs" {
  for_each = toset(["api", "web", "api-migrate"])

  name              = "/ecs/${local.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

locals {
  api_image = "${aws_ecr_repository.app["api"].repository_url}:${var.api_image_tag}"
  web_image = "${aws_ecr_repository.app["web"].repository_url}:${var.web_image_tag}"

  # Shared by the api service and the api-migrate task.
  api_environment = {
    DB_URL          = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${local.db_name}?sslmode=require"
    DB_APP_USER     = local.db_app_user
    COOKIE_SECURE   = "true"
    ALLOWED_ORIGINS = "https://${var.domain_name}"
    DEMO_ENABLED    = tostring(var.demo_enabled)
  }

  # Injected by the ECS agent from Secrets Manager; "<arn>:<json-key>::" selects one JSON field.
  api_secrets = merge(
    {
      DB_APP_PASSWORD         = "${aws_secretsmanager_secret.app["db-app"].arn}:password::"
      JWT_SECRET              = aws_secretsmanager_secret.app["jwt"].arn
      PLATFORM_ADMIN_EMAIL    = "${aws_secretsmanager_secret.app["platform-admin"].arn}:email::"
      PLATFORM_ADMIN_PASSWORD = "${aws_secretsmanager_secret.app["platform-admin"].arn}:password::"
    },
    var.demo_enabled ? { DEMO_PASSWORD = aws_secretsmanager_secret.app["demo"].arn } : {},
  )

  log_options = {
    for name, group in aws_cloudwatch_log_group.ecs : name => {
      awslogs-group         = group.name
      awslogs-region        = var.aws_region
      awslogs-stream-prefix = name
    }
  }
}

# --- Task definitions ---------------------------------------------------------
# Terraform owns the shape of each task definition (env, secrets, sizes, roles). The deploy pipeline
# registers new revisions of the same families with a new image, so the services ignore
# task_definition changes and old revisions are kept (skip_destroy) for rollbacks.

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.ecs_execution["app"].arn
  task_role_arn            = aws_iam_role.task["api"].arn
  skip_destroy             = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = "api"
    image        = local.api_image
    essential    = true
    portMappings = [{ containerPort = local.api_port, protocol = "tcp" }]
    environment  = [for k, v in merge(local.api_environment, { SPRING_PROFILES_ACTIVE = "aws" }) : { name = k, value = v }]
    secrets      = [for k, v in local.api_secrets : { name = k, valueFrom = v }]

    # Liveness only; readiness is the ALB target-group check.
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://127.0.0.1:${local.api_port}/actuator/health/liveness > /dev/null || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 90
    }

    logConfiguration = { logDriver = "awslogs", options = local.log_options["api"] }
  }])
}

# One-off task run by the pipeline before each API rollout: Flyway migrations as akshara_owner, then exit.
# It is the only task definition that receives the owner password.
resource "aws_ecs_task_definition" "api_migrate" {
  family                   = "${local.name}-api-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.ecs_execution["migrate"].arn
  task_role_arn            = aws_iam_role.task["api"].arn
  skip_destroy             = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "api-migrate"
    image     = local.api_image
    essential = true
    environment = [
      for k, v in merge(local.api_environment, {
        SPRING_PROFILES_ACTIVE = "aws,migrate"
        DB_OWNER_USER          = local.db_owner_user
      }) : { name = k, value = v }
    ]
    secrets = [
      for k, v in merge(local.api_secrets, {
        DB_OWNER_PASSWORD = "${aws_secretsmanager_secret.app["db-owner"].arn}:password::"
      }) : { name = k, valueFrom = v }
    ]

    logConfiguration = { logDriver = "awslogs", options = local.log_options["api-migrate"] }
  }])
}

resource "aws_ecs_task_definition" "web" {
  family                   = "${local.name}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.web_cpu
  memory                   = var.web_memory
  execution_role_arn       = aws_iam_role.ecs_execution["app"].arn
  task_role_arn            = aws_iam_role.task["web"].arn
  skip_destroy             = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = "web"
    image        = local.web_image
    essential    = true
    portMappings = [{ containerPort = local.web_port, protocol = "tcp" }]
    environment = [
      # The ALB sends /api/* to the API directly, so the Next.js /api rewrite is never used in AWS.
      # API_URL points at the public origin for anything server-side that still reads it.
      { name = "API_URL", value = "https://${var.domain_name}" },
      # Next.js standalone binds to $HOSTNAME, which Fargate otherwise sets to the task's host name.
      { name = "HOSTNAME", value = "0.0.0.0" },
      { name = "NODE_ENV", value = "production" },
      { name = "PORT", value = tostring(local.web_port) },
    ]

    logConfiguration = { logDriver = "awslogs", options = local.log_options["web"] }
  }])
}

# --- Services and autoscaling ---------------------------------------------------

locals {
  ecs_services = {
    api = {
      task_definition   = aws_ecs_task_definition.api.arn
      target_group_arn  = aws_lb_target_group.api.arn
      security_group_id = aws_security_group.api.id
      port              = local.api_port
      desired_count     = var.api_desired_count
      min_count         = var.api_min_count
      max_count         = var.api_max_count
      # Spring Boot needs longer than Next.js to pass its first readiness check.
      grace_period = 120
    }
    web = {
      task_definition   = aws_ecs_task_definition.web.arn
      target_group_arn  = aws_lb_target_group.web.arn
      security_group_id = aws_security_group.web.id
      port              = local.web_port
      desired_count     = var.web_desired_count
      min_count         = var.web_min_count
      max_count         = var.web_max_count
      grace_period      = 60
    }
  }
}

resource "aws_ecs_service" "app" {
  for_each = local.ecs_services

  name                              = each.key
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = each.value.task_definition
  desired_count                     = each.value.desired_count
  launch_type                       = "FARGATE"
  enable_execute_command            = var.enable_execute_command
  health_check_grace_period_seconds = each.value.grace_period
  propagate_tags                    = "SERVICE"
  enable_ecs_managed_tags           = true

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [each.value.security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = each.value.target_group_arn
    container_name   = each.key
    container_port   = each.value.port
  }

  lifecycle {
    # Image rollouts belong to the deploy pipeline and task counts to autoscaling.
    ignore_changes = [task_definition, desired_count]
  }

  # Target groups must be attached to a listener before a service can register into them, and
  # secrets must have a value before the first task starts.
  depends_on = [
    aws_lb_listener_rule.api,
    aws_lb_listener.https,
    aws_secretsmanager_secret_version.app,
  ]
}

resource "aws_appautoscaling_target" "app" {
  for_each = aws_ecs_service.app

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${each.value.name}"
  min_capacity       = local.ecs_services[each.key].min_count
  max_capacity       = local.ecs_services[each.key].max_count
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each = aws_appautoscaling_target.app

  name               = "${local.name}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = each.value.service_namespace
  scalable_dimension = each.value.scalable_dimension
  resource_id        = each.value.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_out_cooldown = 60
    scale_in_cooldown  = 300

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
