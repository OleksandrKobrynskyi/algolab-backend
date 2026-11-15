import * as cdk from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { InfraStack } from '../lib/infra-stack';

describe('InfraStack', () => {
  const app = new cdk.App();
  const stack = new InfraStack(app, 'TestStack');
  const template = Template.fromStack(stack);

  test('DynamoDB table is on-demand with TestNameIndex GSI', () => {
    template.hasResourceProperties('AWS::DynamoDB::Table', {
      BillingMode: 'PAY_PER_REQUEST',
      GlobalSecondaryIndexes: Match.arrayWith([
        Match.objectLike({
          IndexName: 'TestNameIndex',
          KeySchema: [
            { AttributeName: 'testName', KeyType: 'HASH' },
            { AttributeName: 'createdAt', KeyType: 'RANGE' },
          ],
        }),
      ]),
    });
  });

  test('two Java 21 Lambdas with JAVA_TOOL_OPTIONS', () => {
    const lambdas = template.findResources('AWS::Lambda::Function', {
      Properties: { Runtime: 'java21' },
    });
    expect(Object.keys(lambdas)).toHaveLength(2);
    template.allResourcesProperties('AWS::Lambda::Function', {
      Runtime: 'java21',
      Environment: {
        Variables: Match.objectLike({
          JAVA_TOOL_OPTIONS: '-XX:+TieredCompilation -XX:TieredStopAtLevel=1',
        }),
      },
    });
  });

  test('stage throttling configured', () => {
    template.hasResourceProperties('AWS::ApiGateway::Stage', {
      MethodSettings: Match.arrayWith([
        Match.objectLike({ ThrottlingRateLimit: 20, ThrottlingBurstLimit: 50 }),
      ]),
    });
  });

  test('request validator validates body', () => {
    template.hasResourceProperties('AWS::ApiGateway::RequestValidator', {
      ValidateRequestBody: true,
    });
  });

  test('gateway responses for 4XX and 5XX', () => {
    for (const type of ['DEFAULT_4XX', 'DEFAULT_5XX']) {
      template.hasResourceProperties('AWS::ApiGateway::GatewayResponse', {
        ResponseType: type,
        ResponseParameters: {
          'gatewayresponse.header.Access-Control-Allow-Origin': "'*'",
          'gatewayresponse.header.Access-Control-Allow-Headers':
            "'Content-Type,X-Admin-Login,X-Admin-Password'",
          'gatewayresponse.header.Access-Control-Allow-Methods': "'OPTIONS,GET,POST'",
        },
      });
    }
  });

  test('outputs exist', () => {
    template.hasOutput('ApiEndpointUrl', {});
    template.hasOutput('DynamoDbTableName', {});
  });
});
