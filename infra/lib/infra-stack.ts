import * as cdk from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import { Construct } from 'constructs';
import * as path from 'path';

/**
 * Origins allowed to call the API when ALLOWED_ORIGINS is not configured.
 * Never fall back to the `*` wildcard: CORS is enforced against an explicit
 * allowlist instead.
 */
const defaultAllowedOrigins = ['https://oleksandr2903.github.io', 'http://localhost:5173'];

function resolveAllowedOrigins(): string[] {
  const configured = process.env.ALLOWED_ORIGINS;
  if (!configured) {
    return defaultAllowedOrigins;
  }
  const origins = configured
    .split(',')
    .map((origin) => origin.trim())
    .filter((origin) => origin.length > 0);
  return origins.length > 0 ? origins : defaultAllowedOrigins;
}

/**
 * Reserved concurrency is opt-in: a value that exceeds the account quota would
 * make the stack undeployable, so only apply a valid positive integer.
 */
function resolveReservedConcurrency(): number | undefined {
  const configured = process.env.LAMBDA_RESERVED_CONCURRENCY;
  if (!configured) {
    return undefined;
  }
  const value = Number(configured.trim());
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

export class InfraStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const jarPath = path.join(__dirname, '../../backend/target/algolab-backend-1.0.jar');

    const table = new dynamodb.Table(this, 'AlgoLabResults', {
      partitionKey: { name: 'submissionId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    table.addGlobalSecondaryIndex({
      indexName: 'TestNameIndex',
      partitionKey: { name: 'testName', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'createdAt', type: dynamodb.AttributeType.STRING },
    });

    const javaToolOptions = '-XX:+TieredCompilation -XX:TieredStopAtLevel=1';

    const allowedOrigins = resolveAllowedOrigins();
    const allowedOriginsValue = allowedOrigins.join(',');
    const primaryOrigin = allowedOrigins[0];
    const reservedConcurrency = resolveReservedConcurrency();

    const postResultHandler = new lambda.Function(this, 'PostResultHandler', {
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.algolab.PostResultHandler::handleRequest',
      code: lambda.Code.fromAsset(jarPath),
      memorySize: 512,
      timeout: cdk.Duration.seconds(20),
      environment: {
        TABLE_NAME: table.tableName,
        ALLOWED_ORIGINS: allowedOriginsValue,
        JAVA_TOOL_OPTIONS: javaToolOptions,
      },
      ...(reservedConcurrency !== undefined
        ? { reservedConcurrentExecutions: reservedConcurrency }
        : {}),
    });

    const getResultsHandler = new lambda.Function(this, 'GetResultsHandler', {
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.algolab.GetResultsHandler::handleRequest',
      code: lambda.Code.fromAsset(jarPath),
      memorySize: 512,
      timeout: cdk.Duration.seconds(20),
      environment: {
        TABLE_NAME: table.tableName,
        GSI_NAME: 'TestNameIndex',
        ADMIN_LOGIN: process.env.ADMIN_LOGIN || 'teacher',
        ADMIN_PASSWORD: process.env.ADMIN_PASSWORD || 'SecretPassword123',
        ALLOWED_ORIGINS: allowedOriginsValue,
        JAVA_TOOL_OPTIONS: javaToolOptions,
      },
      ...(reservedConcurrency !== undefined
        ? { reservedConcurrentExecutions: reservedConcurrency }
        : {}),
    });

    // Least privilege: each handler only gets the DynamoDB actions it uses.
    postResultHandler.addToRolePolicy(new iam.PolicyStatement({
      actions: ['dynamodb:PutItem'],
      resources: [table.tableArn],
    }));

    getResultsHandler.addToRolePolicy(new iam.PolicyStatement({
      actions: ['dynamodb:Query', 'dynamodb:Scan'],
      resources: [table.tableArn, `${table.tableArn}/index/*`],
    }));

    const api = new apigateway.RestApi(this, 'AlgoLabApi', {
      restApiName: 'AlgoLabApi',
      defaultCorsPreflightOptions: {
        allowOrigins: allowedOrigins,
        allowMethods: ['GET', 'POST', 'OPTIONS'],
        allowHeaders: ['Content-Type', 'X-Admin-Login', 'X-Admin-Password'],
        maxAge: cdk.Duration.hours(1),
      },
      deployOptions: {
        throttlingRateLimit: 50,
        throttlingBurstLimit: 100,
      },
    });

    // API Gateway requires gateway response header values to be static,
    // single-quoted strings. A dynamic `method.request.header.Origin` value is
    // rejected by CloudFormation, so use the primary allowed origin here.
    const corsResponseHeaders = {
      'Access-Control-Allow-Origin': `'${primaryOrigin}'`,
      'Access-Control-Allow-Headers': "'Content-Type,X-Admin-Login,X-Admin-Password'",
      'Access-Control-Allow-Methods': "'OPTIONS,GET,POST'",
    };

    api.addGatewayResponse('Default4xxCors', {
      type: apigateway.ResponseType.DEFAULT_4XX,
      responseHeaders: corsResponseHeaders,
    });

    api.addGatewayResponse('Default5xxCors', {
      type: apigateway.ResponseType.DEFAULT_5XX,
      responseHeaders: corsResponseHeaders,
    });

    api.addGatewayResponse('RequestTooLargeCors', {
      type: apigateway.ResponseType.REQUEST_TOO_LARGE,
      responseHeaders: corsResponseHeaders,
    });

    const postResultModel = new apigateway.Model(this, 'PostResultModel', {
      restApi: api,
      contentType: 'application/json',
      modelName: 'PostResultModel',
      schema: {
        schema: apigateway.JsonSchemaVersion.DRAFT4,
        title: 'PostResult',
        type: apigateway.JsonSchemaType.OBJECT,
        required: ['studentName', 'testName', 'score', 'answers'],
        properties: {
          studentName: { type: apigateway.JsonSchemaType.STRING, minLength: 2, maxLength: 100 },
          testName: { type: apigateway.JsonSchemaType.STRING, minLength: 1, maxLength: 100 },
          score: {
            type: apigateway.JsonSchemaType.INTEGER,
            minimum: 0,
            maximum: 1000,
          },
          answers: {
            type: apigateway.JsonSchemaType.ARRAY,
            maxItems: 200,
            items: {
              type: apigateway.JsonSchemaType.STRING,
              minLength: 1,
              maxLength: 256,
            },
          },
        },
      },
    });

    const bodyValidator = new apigateway.RequestValidator(this, 'PostResultBodyValidator', {
      restApi: api,
      requestValidatorName: 'PostResultBodyValidator',
      validateRequestBody: true,
      validateRequestParameters: false,
    });

    const results = api.root.addResource('results');
    results.addMethod('POST', new apigateway.LambdaIntegration(postResultHandler), {
      requestValidator: bodyValidator,
      requestModels: { 'application/json': postResultModel },
    });
    results.addMethod('GET', new apigateway.LambdaIntegration(getResultsHandler));

    new cdk.CfnOutput(this, 'ApiEndpointUrl', {
      value: api.url,
      description: 'API Gateway endpoint URL for AlgoLab',
    });

    new cdk.CfnOutput(this, 'DynamoDbTableName', {
      value: table.tableName,
      description: 'DynamoDB table storing AlgoLab results',
    });
  }
}
