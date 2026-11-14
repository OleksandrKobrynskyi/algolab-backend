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

    const api = new apigateway.RestApi(this, 'AlgoLabApi', {
      restApiName: 'AlgoLabApi',
      defaultCorsPreflightOptions: {
        allowOrigins: apigateway.Cors.ALL_ORIGINS,
        allowMethods: apigateway.Cors.ALL_METHODS,
        allowHeaders: ['Content-Type', 'X-Admin-Login', 'X-Admin-Password'],
      },
      deployOptions: {
        throttlingRateLimit: 20,
        throttlingBurstLimit: 50,
      },
    });

    api.addGatewayResponse('Default4xxCors', {
      type: apigateway.ResponseType.DEFAULT_4XX,
      responseHeaders: {
        'Access-Control-Allow-Origin': "'*'",
        'Access-Control-Allow-Headers': "'*'",
      },
    });

    api.addGatewayResponse('Default5xxCors', {
      type: apigateway.ResponseType.DEFAULT_5XX,
      responseHeaders: {
        'Access-Control-Allow-Origin': "'*'",
        'Access-Control-Allow-Headers': "'*'",
      },
    });

    const results = api.root.addResource('results');
    results.addMethod('POST', new apigateway.LambdaIntegration(postResultHandler));
    results.addMethod('GET', new apigateway.LambdaIntegration(getResultsHandler));

    new cdk.CfnOutput(this, 'ApiUrl', {
      value: api.url,
      description: 'API Gateway endpoint URL for AlgoLab',
    });
  }
}
