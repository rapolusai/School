# Email through Amazon SES (EMAIL_SENDER=ses when email_enabled). The domain is verified with DKIM: add the
# three CNAME records from `terraform output ses_dkim_records` at the DNS provider. A new SES account starts in
# the sandbox (it can only send to verified addresses); request production access in the SES console before
# turning email_enabled on for real schools.

locals {
  email_from = coalesce(var.email_from_address, "no-reply@${var.domain_name}")
}

resource "aws_sesv2_email_identity" "domain" {
  email_identity         = var.domain_name
  configuration_set_name = aws_sesv2_configuration_set.main.configuration_set_name
}

resource "aws_sesv2_configuration_set" "main" {
  configuration_set_name = local.name

  delivery_options {
    tls_policy = "REQUIRE"
  }

  reputation_options {
    reputation_metrics_enabled = true
  }

  # Hard bounces and complaints go to the account suppression list, so they are never mailed again.
  suppression_options {
    suppressed_reasons = ["BOUNCE", "COMPLAINT"]
  }
}

data "aws_iam_policy_document" "api_email" {
  statement {
    sid     = "SendFromTheSchoolDomain"
    actions = ["ses:SendEmail"]
    resources = [
      aws_sesv2_email_identity.domain.arn,
      aws_sesv2_configuration_set.main.arn,
    ]

    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [local.email_from]
    }
  }
}

resource "aws_iam_role_policy" "api_email" {
  name   = "email"
  role   = aws_iam_role.task["api"].id
  policy = data.aws_iam_policy_document.api_email.json
}
