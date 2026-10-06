import os
import re
from datetime import datetime, timedelta, timezone
from uuid import UUID

import boto3

from report_csv import render_csv

SAFE_HASH = re.compile(r"^[a-f0-9]{64}$")
DATE_KEY = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _validated(event):
    request_id = str(UUID(event["requestId"]))
    subject_hash = event["subjectHash"]
    from_date = event["fromDate"]
    to_date = event["toDate"]
    time_zone = event["timeZone"]
    rows = event["rows"]
    if not SAFE_HASH.fullmatch(subject_hash):
        raise ValueError("Invalid subject")
    if not DATE_KEY.fullmatch(from_date) or not DATE_KEY.fullmatch(to_date):
        raise ValueError("Invalid date")
    if not isinstance(time_zone, str) or not 1 <= len(time_zone) <= 80:
        raise ValueError("Invalid timezone")
    if not isinstance(rows, list) or len(rows) > int(os.environ.get("MAX_ROWS", "5000")):
        raise ValueError("Invalid row count")
    return request_id, subject_hash, from_date, to_date, rows


def lambda_handler(event, _context):
    request_id, subject_hash, from_date, to_date, rows = _validated(event)
    bucket = os.environ["REPORTS_BUCKET"]
    environment = os.environ["ENVIRONMENT_NAME"]
    expiry = min(600, max(60, int(os.environ.get("URL_EXPIRY_SECONDS", "600"))))
    key = f"reports/{environment}/{subject_hash}/{request_id}.csv"
    filename = f"stressguard-history-{from_date}-to-{to_date}.csv"
    client = boto3.client("s3")
    client.put_object(
        Bucket=bucket,
        Key=key,
        Body=render_csv(rows),
        ContentType="text/csv; charset=utf-8",
        ContentDisposition=f'attachment; filename="{filename}"',
        ServerSideEncryption="AES256",
    )
    download_url = client.generate_presigned_url(
        "get_object",
        Params={"Bucket": bucket, "Key": key},
        ExpiresIn=expiry,
    )
    expires_at = datetime.now(timezone.utc) + timedelta(seconds=expiry)
    return {"download_url": download_url, "expires_at": expires_at.isoformat()}
