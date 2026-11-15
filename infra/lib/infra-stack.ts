import * as cdk from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import { Construct } from 'constructs';
import * as path from 'path';

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

    const postResultHandler = new lambda.Function(this, 'PostResultHandler', {
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.algolab.PostResultHandler::handleRequest',
      code: lambda.Code.fromAsset(jarPath),
      memorySize: 512,
      timeout: cdk.Duration.seconds(20),
      environment: {
        TABLE_NAME: table.tableName,
        JAVA_TOOL_OPTIONS: javaToolOptions,
      },
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
        JAVA_TOOL_OPTIONS: javaToolOptions,
      },
    });

    table.grantWriteData(postResultHandler);
    table.grantReadData(getResultsHandler);

    const allowedOrigins = process.env.ALLOWED_ORIGINS
      ? process.env.ALLOWED_ORIGINS.split(',').map((o) => o.trim()).filter((o) => o.length > 0)
      : [];

    const api = new apigateway.RestApi(this, 'AlgoLabApi', {
      restApiName: 'AlgoLabApi',
      defaultCorsPreflightOptions: {
        allowOrigins: allowedOrigins.length > 0 ? allowedOrigins : apigateway.Cors.ALL_ORIGINS,
        allowMethods: ['GET', 'POST', 'OPTIONS'],
        allowHeaders: ['Content-Type', 'X-Admin-Login', 'X-Admin-Password'],
      },
      deployOptions: {
        throttlingRateLimit: 20,
        throttlingBurstLimit: 50,
      },
    });

    const corsResponseHeaders = {
      'Access-Control-Allow-Origin': "'*'",
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
          score: { type: apigateway.JsonSchemaType.NUMBER, minimum: 0, maximum: 1000 },
          answers: { type: apigateway.JsonSchemaType.ARRAY },
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
