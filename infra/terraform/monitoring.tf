# Alarms publish to this topic; nobody is subscribed yet (add an email/chat subscription after apply).
# Left without SSE: CloudWatch cannot publish to a topic encrypted with the AWS-managed SNS key.
resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

locals {
  alarm_actions = [aws_sns_topic.alarms.arn]
  alb_dimension = { LoadBalancer = aws_lb.main.arn_suffix }
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
resource "aws_cloudwatch_metric_alarm" "api_tasks_below_desired" {
  alarm_name          = "${local.name}-api-tasks-below-desired"
  alarm_description   = "Fewer API tasks running than desired for 5 minutes (crash loop, failed deployment or capacity issue)."
  evaluation_periods  = 5
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "missing"
    expression  = "desired - running"
    label       = "API tasks below desired"
    return_data = true
  }

  metric_query {
    id = "desired"

    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "DesiredTaskCount"
      dimensions  = { ClusterName = aws_ecs_cluster.main.name, ServiceName = aws_ecs_service.app["api"].name }
      stat        = "Average"
      period      = 60
    }
  }

  metric_query {
    id = "running"

    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "RunningTaskCount"
      dimensions  = { ClusterName = aws_ecs_cluster.main.name, ServiceName = aws_ecs_service.app["api"].name }
      stat        = "Average"
      period      = 60
    }
  }
}
