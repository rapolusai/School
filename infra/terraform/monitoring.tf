# Alarms publish to this topic; every address in alarm_emails is subscribed (each must confirm AWS's email).
# Left without SSE: CloudWatch cannot publish to a topic encrypted with the AWS-managed SNS key.
resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

resource "aws_sns_topic_subscription" "alarm_email" {
  for_each = toset(var.alarm_emails)

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = each.value
}

locals {
  alarm_actions = [aws_sns_topic.alarms.arn]
  alb_dimension = { LoadBalancer = aws_lb.main.arn_suffix }
}

resource "aws_cloudwatch_metric_alarm" "api_latency" {
  alarm_name          = "${local.name}-api-latency-p95"
  alarm_description   = "API p95 response time above ${var.alarm_latency_p95_seconds}s for 10 minutes (slow database, too few tasks)."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = aws_lb_target_group.api.arn_suffix }
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alarm_latency_p95_seconds
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${local.name}-rds-connections"
  alarm_description   = "More than 80% of the database's connection slots in use for 10 minutes (pool sizes x tasks too high)."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  # db.t4g.medium allows about 400 connections; each API task holds at most DB_POOL_SIZE (10).
  threshold          = 320
  treat_missing_data = "missing"
  alarm_actions      = local.alarm_actions
  ok_actions         = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name}-alb-5xx"
  alarm_description   = "The load balancer itself returned more than ${var.alarm_5xx_threshold} 5xx responses in 5 minutes (no healthy targets, timeouts)."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  dimensions          = local.alb_dimension
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alarm_5xx_threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "target_5xx" {
  alarm_name          = "${local.name}-target-5xx"
  alarm_description   = "The api/web containers returned more than ${var.alarm_5xx_threshold} 5xx responses in 5 minutes."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = local.alb_dimension
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alarm_5xx_threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${local.name}-rds-cpu"
  alarm_description   = "Database CPU above 80% for 15 minutes."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${local.name}-rds-free-storage"
  alarm_description   = "Less than 5 GiB of database storage left (check storage autoscaling has headroom below db_max_allocated_storage)."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 5 * 1024 * 1024 * 1024
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Container Insights metrics; 5 consecutive minutes so normal deployments and scale-outs do not fire it.
resource "aws_cloudwatch_metric_alarm" "tasks_below_desired" {
  for_each = aws_ecs_service.app

  alarm_name          = "${local.name}-${each.key}-tasks-below-desired"
  alarm_description   = "Fewer ${each.key} tasks running than desired for 5 minutes (crash loop, failed deployment or capacity issue)."
  evaluation_periods  = 5
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "missing"
    expression  = "desired - running"
    label       = "${each.key} tasks below desired"
    return_data = true
  }

  metric_query {
    id = "desired"

    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "DesiredTaskCount"
      dimensions  = { ClusterName = aws_ecs_cluster.main.name, ServiceName = each.value.name }
      stat        = "Average"
      period      = 60
    }
  }

  metric_query {
    id = "running"

    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "RunningTaskCount"
      dimensions  = { ClusterName = aws_ecs_cluster.main.name, ServiceName = each.value.name }
      stat        = "Average"
      period      = 60
    }
  }
}

# --- Uptime from outside AWS's network --------------------------------------------
# Route 53 checkers in several regions open https://<domain_name>/login every 30 seconds. The page is served by
# the web task and needs no sign-in. Its metric exists only in us-east-1, hence the second topic there.

resource "aws_route53_health_check" "site" {
  count = var.uptime_check_enabled ? 1 : 0

  fqdn              = var.domain_name
  port              = 443
  type              = "HTTPS_STR_MATCH"
  resource_path     = "/login"
  search_string     = "Akshara"
  request_interval  = 30
  failure_threshold = 3
  measure_latency   = true

  tags = {
    Name = "${local.name}-site"
  }
}

resource "aws_sns_topic" "uptime" {
  count    = var.uptime_check_enabled ? 1 : 0
  provider = aws.us_east_1

  name = "${local.name}-uptime"
}

resource "aws_sns_topic_subscription" "uptime_email" {
  for_each = var.uptime_check_enabled ? toset(var.alarm_emails) : toset([])
  provider = aws.us_east_1

  topic_arn = aws_sns_topic.uptime[0].arn
  protocol  = "email"
  endpoint  = each.value
}

resource "aws_cloudwatch_metric_alarm" "site_down" {
  count    = var.uptime_check_enabled ? 1 : 0
  provider = aws.us_east_1

  alarm_name          = "${local.name}-site-down"
  alarm_description   = "https://${var.domain_name}/login failed from outside AWS for 2 minutes (DNS, certificate, load balancer or web tasks)."
  namespace           = "AWS/Route53"
  metric_name         = "HealthCheckStatus"
  dimensions          = { HealthCheckId = aws_route53_health_check.site[0].id }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.uptime[0].arn]
  ok_actions          = [aws_sns_topic.uptime[0].arn]
}

# --- One dashboard for the environment --------------------------------------------

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = local.name

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title  = "Requests and errors (load balancer)"
          region = var.aws_region
          stat   = "Sum"
          period = 300
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", aws_lb.main.arn_suffix],
            [".", "HTTPCode_Target_4XX_Count", ".", "."],
            [".", "HTTPCode_Target_5XX_Count", ".", "."],
            [".", "HTTPCode_ELB_5XX_Count", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title  = "API response time (p50, p95, p99 seconds)"
          region = var.aws_region
          period = 300
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.main.arn_suffix, "TargetGroup", aws_lb_target_group.api.arn_suffix, { stat = "p50" }],
            ["...", { stat = "p95" }],
            ["...", { stat = "p99" }],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title  = "Containers: CPU and memory (%)"
          region = var.aws_region
          stat   = "Average"
          period = 300
          metrics = flatten([
            for name, service in aws_ecs_service.app : [
              ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", service.name, { label = "${name} CPU" }],
              [".", "MemoryUtilization", ".", ".", ".", ".", { label = "${name} memory" }],
            ]
          ])
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title  = "Running tasks"
          region = var.aws_region
          stat   = "Average"
          period = 60
          metrics = [
            for name, service in aws_ecs_service.app :
            ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", service.name, { label = name }]
          ]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title  = "Database: CPU (%) and connections"
          region = var.aws_region
          stat   = "Average"
          period = 300
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", aws_db_instance.main.identifier],
            [".", "DatabaseConnections", ".", ".", { yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6
        properties = {
          title  = "Database: read and write latency (seconds), free storage"
          region = var.aws_region
          stat   = "Average"
          period = 300
          metrics = [
            ["AWS/RDS", "ReadLatency", "DBInstanceIdentifier", aws_db_instance.main.identifier],
            [".", "WriteLatency", ".", "."],
            [".", "FreeStorageSpace", ".", ".", { yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 18, width = 24, height = 6
        properties = {
          title  = "Firewall: allowed and blocked requests"
          region = var.aws_region
          stat   = "Sum"
          period = 300
          metrics = [
            ["AWS/WAFV2", "AllowedRequests", "WebACL", aws_wafv2_web_acl.main.name, "Rule", "ALL", "Region", var.aws_region],
            [".", "BlockedRequests", ".", ".", ".", ".", ".", "."],
            [".", "CountedRequests", ".", ".", ".", ".", ".", "."],
          ]
        }
      },
    ]
  })
}
