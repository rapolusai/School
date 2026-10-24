# Generated values are stored in the Terraform state as well as in Secrets Manager, so the state
# bucket must stay encrypted and access-restricted (see README).

resource "random_password" "db_app" {
  length  = 32
  special = false
}

resource "random_password" "db_owner" {
  length  = 32
  special = false
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "random_password" "platform_admin" {
  length           = 24
  override_special = "-_.!@#%+="
}

# Signs sandbox payments and webhooks. Shared by every API task: a key made at start-up per task would make a
# payment started on one task fail verification on another.
resource "random_password" "payments_sandbox" {
  length  = 48
  special = false
}

resource "random_password" "demo" {
  count = var.demo_enabled ? 1 : 0

  length  = 20
  special = false
}

locals {
  # Secret name suffix => description. Kept separate from the values so for_each never sees sensitive data.
  secret_descriptions = merge(
    {
      "db-app"           = "PostgreSQL runtime role used by the API (subject to row-level security)"
      "db-owner"         = "PostgreSQL owner role, used only by the api-migrate task"
      "jwt"              = "HMAC key that signs access tokens"
      "platform-admin"   = "Bootstrap platform administrator login"
      "payments-sandbox" = "Key that signs sandbox payments and webhooks"
    },
    var.demo_enabled ? { "demo" = "Password for the seeded demo school accounts" } : {},
  )

  secret_values = merge(
    {
      "db-app"           = jsonencode({ username = local.db_app_user, password = random_password.db_app.result })
      "db-owner"         = jsonencode({ username = local.db_owner_user, password = random_password.db_owner.result })
      "jwt"              = random_password.jwt.result
      "platform-admin"   = jsonencode({ email = var.platform_admin_email, password = random_password.platform_admin.result })
      "payments-sandbox" = random_password.payments_sandbox.result
    },
    var.demo_enabled ? { "demo" = random_password.demo[0].result } : {},
  )
}

resource "aws_secretsmanager_secret" "app" {
  for_each = local.secret_descriptions

  name                    = "${local.project}/${var.environment}/${each.key}"
  description             = each.value
  kms_key_id              = aws_kms_key.secrets.arn
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "app" {
  for_each = aws_secretsmanager_secret.app

  secret_id     = each.value.id
  secret_string = local.secret_values[each.key]
}

# Secrets whose values come from outside (a provider's dashboard), so Terraform creates the empty secret and
# never sees the value. The owner stores it once in the console or with `aws secretsmanager put-secret-value`
# (README, "Payments"); the API reads it only after the matching switch (payments_gateway) is turned on.
resource "aws_secretsmanager_secret" "external" {
  for_each = {
    "payments-razorpay" = "Razorpay key id, key secret and webhook secret, as JSON {\"key_id\",\"key_secret\",\"webhook_secret\"}"
  }

  name                    = "${local.project}/${var.environment}/${each.key}"
  description             = each.value
  kms_key_id              = aws_kms_key.secrets.arn
  recovery_window_in_days = 7
}
