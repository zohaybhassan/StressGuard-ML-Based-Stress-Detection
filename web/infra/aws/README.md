# StressGuard report infrastructure

This AWS SAM stack creates one private, encrypted S3 bucket and one non-public
Lambda function per environment. Reports expire from S3 after one day and each
download URL expires after ten minutes.

## Deploy

Install AWS SAM CLI, authenticate to the intended AWS account, then run from
`web/infra/aws`:

```sh
sam build
sam deploy --guided --parameter-overrides EnvironmentName=development
```

Repeat with separate stacks/accounts or roles for `staging` and `production`.
Attach the output `VercelInvokePolicyArn` to the narrowly scoped IAM principal
used by the matching Vercel environment. Set `AWS_REGION` and
`AWS_REPORT_FUNCTION_NAME` in Vercel. Supply AWS credentials through Vercel's
server-only environment or workload identity integration—never as
`NEXT_PUBLIC_*` values.

The bucket name is injected into Lambda by CloudFormation and is not needed by
the Next.js app. The Lambda derives every object key server-side; neither the
browser nor the Next.js request can choose a key.

## Local validation

```sh
sam validate --lint
python -m unittest discover -s tests
```

Local History browsing works with Supabase alone. Export displays a friendly
configuration message until the AWS function name and region are provided.
