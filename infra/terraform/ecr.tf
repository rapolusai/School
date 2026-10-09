# Repository names carry no environment, so an AWS account holds one set (see README, "Adding prod").
resource "aws_ecr_repository" "app" {
  for_each = toset(["api", "web"])

  name                 = "${local.project}-${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  # KMS with the AWS-managed aws/ecr key. Encryption settings cannot be changed after creation.
  encryption_configuration {
    encryption_type = "KMS"
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  for_each = aws_ecr_repository.app

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 30 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 30
      }
      action = { type = "expire" }
    }]
  })
}
