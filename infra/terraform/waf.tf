locals {
  # AWS managed rule group => rule priority (lower runs first). Priorities 1 and 2 are the rate limits.
  waf_managed_rule_groups = {
    AWSManagedRulesAmazonIpReputationList = 0
    AWSManagedRulesCommonRuleSet          = 3
    AWSManagedRulesKnownBadInputsRuleSet  = 4
    AWSManagedRulesSQLiRuleSet            = 5
  }

  # Credential and signup endpoints get the stricter per-IP limit.
  waf_login_paths = ["/api/auth/login", "/api/platform/auth/login", "/api/public/signup"]
}

resource "aws_wafv2_web_acl" "main" {
  name        = local.name
  description = "Akshara ${var.environment} public load balancer"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  dynamic "rule" {
    for_each = local.waf_managed_rule_groups

    content {
      name     = rule.key
      priority = rule.value

      override_action {
        none {}
      }

      statement {
        managed_rule_group_statement {
          name        = rule.key
          vendor_name = "AWS"
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${local.name}-${rule.key}"
        sampled_requests_enabled   = true
      }
    }
  }

  rule {
    name     = "rate-limit-login"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_login_rate_limit
        evaluation_window_sec = 300
        aggregate_key_type    = "IP"

        scope_down_statement {
          or_statement {
            dynamic "statement" {
              for_each = local.waf_login_paths

              content {
                # STARTS_WITH after decoding and normalising, so "/api/auth/login/", "%6cogin" or
                # ";param" suffixes (which the API would still route to the handler) are counted too.
                byte_match_statement {
                  search_string         = statement.value
                  positional_constraint = "STARTS_WITH"

                  field_to_match {
                    uri_path {}
                  }

                  text_transformation {
                    priority = 0
                    type     = "URL_DECODE"
                  }

                  text_transformation {
                    priority = 1
                    type     = "NORMALIZE_PATH"
                  }
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-rate-limit-login"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "rate-limit-ip"
    priority = 2

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_rate_limit
        evaluation_window_sec = 300
        aggregate_key_type    = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-rate-limit-ip"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = local.name
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.main.arn
  web_acl_arn  = aws_wafv2_web_acl.main.arn
}

# --- Optional request logging -----------------------------------------------

resource "aws_cloudwatch_log_group" "waf" {
  count = var.waf_logging_enabled ? 1 : 0

  # WAF only delivers to log groups whose name starts with "aws-waf-logs-".
  name              = "aws-waf-logs-${local.name}"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "waf_logs" {
  count = var.waf_logging_enabled ? 1 : 0

  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.waf[0].arn}:*"]

    principals {
      type        = "Service"
      identifiers = ["delivery.logs.amazonaws.com"]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:logs:${var.aws_region}:${local.account_id}:*"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_cloudwatch_log_resource_policy" "waf" {
  count = var.waf_logging_enabled ? 1 : 0

  policy_name     = "${local.name}-waf-logs"
  policy_document = data.aws_iam_policy_document.waf_logs[0].json
}

resource "aws_wafv2_web_acl_logging_configuration" "main" {
  count = var.waf_logging_enabled ? 1 : 0

  resource_arn            = aws_wafv2_web_acl.main.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf[0].arn]

  # Session cookies and bearer tokens must never land in logs.
  redacted_fields {
    single_header {
      name = "cookie"
    }
  }

  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  depends_on = [aws_cloudwatch_log_resource_policy.waf]
}
