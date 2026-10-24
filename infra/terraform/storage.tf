# Uploaded files (homework, certificates' photos, bills, imports): one private bucket per environment, used by
# the API through S3FileStore (FILES_STORE=s3). The database keeps each file's name, type, size, hash, owner and
# school; the bytes live here under "<tenant id>/<file id>". Downloads still go through GET /api/files/{id}, so
# the owning module's access policy and the school's row-level security decide who may read a file.

resource "aws_kms_key" "files" {
  description             = "${local.name} uploaded files"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "files" {
  name          = "alias/${local.name}-files"
  target_key_id = aws_kms_key.files.key_id
}

resource "aws_s3_bucket" "files" {
  bucket = "${local.name}-files-${local.account_id}"
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket = aws_s3_bucket.files.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.files.arn
    }
    bucket_key_enabled = true
  }
}

# Versioning keeps an overwritten or deleted file for files_noncurrent_retention_days (mistakes, ransomware).
resource "aws_s3_bucket_versioning" "files" {
  bucket = aws_s3_bucket.files.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    id     = "expire-old-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.files_noncurrent_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }

    expiration {
      expired_object_delete_marker = true
    }
  }

  depends_on = [aws_s3_bucket_versioning.files]
}

data "aws_iam_policy_document" "files_bucket" {
  statement {
    sid     = "TlsOnly"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.files.arn,
      "${aws_s3_bucket.files.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "files" {
  bucket = aws_s3_bucket.files.id
  policy = data.aws_iam_policy_document.files_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.files]
}

# --- What the API may do with the bucket (task role) ------------------------------
# The api-migrate task shares this role; the one-off copy of existing files from the database to S3
# (FILES_COPY_TO_S3, docs/adr/0004-file-storage.md) runs there.

data "aws_iam_policy_document" "api_files" {
  statement {
    sid       = "ReadWriteObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.files.arn}/*"]
  }

  statement {
    sid       = "UseFilesKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.files.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "api_files" {
  name   = "files"
  role   = aws_iam_role.task["api"].id
  policy = data.aws_iam_policy_document.api_files.json
}
