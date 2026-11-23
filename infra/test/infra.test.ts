import * as cdk from 'aws-cdk-lib/core';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { InfraStack } from '../lib/infra-stack';

const DEFAULT_ALLOWED_ORIGINS =
  'https://oleksandrkobrynskyi.github.io,http://localhost:5173';

type EnvOverrides = Record<string, string | undefined>;

/**
 * Synthesises the stack with a controlled set of environment variables so the
 * assertions do not depend on the developer's shell.
 */
function synthStack(id: string, env: EnvOverrides = {}): Template {
  const previous: EnvOverrides = {};
  for (const [key, value] of Object.entries(env)) {
    previous[key] = process.env[key];
    if (value === undefined) {
      delete process.env[key];
    } else {
      process.env[key] = value;
    }
  }

  try {
    const app = new cdk.App();
    const stack = new InfraStack(app, id);
    return Template.fromStack(stack);
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
}

const defaultEnv: EnvOverrides = {
  ALLOWED_ORIGINS: undefined,
  LAMBDA_RESERVED_CONCURRENCY: undefined,
};

describe('InfraStack', () => {
  test('DynamoDB table is on-demand with TestNameIndex GSI', () => {
    const template = synthStack('DynamoStack', defaultEnv);

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

  test('TestNameIndex sort key stays a STRING attribute', () => {
    const template = synthStack('GsiTypeStack', defaultEnv);

    template.hasResourceProperties('AWS::DynamoDB::Table', {
      AttributeDefinitions: Match.arrayWith([
        { AttributeName: 'createdAt', AttributeType: 'S' },
      ]),
    });
  });

  test('two Java 21 Lambdas with JAVA_TOOL_OPTIONS', () => {
    const template = synthStack('LambdaStack', defaultEnv);

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

  test('both Lambdas receive the default ALLOWED_ORIGINS allowlist', () => {
    const template = synthStack('CorsEnvStack', defaultEnv);

    template.allResourcesProperties('AWS::Lambda::Function', {
      Environment: {
        Variables: Match.objectLike({
          ALLOWED_ORIGINS: DEFAULT_ALLOWED_ORIGINS,
        }),
      },
    });
  });

  test('ALLOWED_ORIGINS overrides the default allowlist', () => {
    const template = synthStack('CorsOverrideStack', {
      ALLOWED_ORIGINS: ' https://example.com , https://other.example ',
    });

    template.allResourcesProperties('AWS::Lambda::Function', {
      Environment: {
        Variables: Match.objectLike({
          ALLOWED_ORIGINS: 'https://example.com,https://other.example',
        }),
      },
    });

    template.hasResourceProperties('AWS::ApiGateway::GatewayResponse', {
      ResponseType: 'DEFAULT_4XX',
      ResponseParameters: Match.objectLike({
        'gatewayresponse.header.Access-Control-Allow-Origin': "'https://example.com'",
      }),
    });
  });

  test('blank ALLOWED_ORIGINS falls back to the default allowlist', () => {
    const template = synthStack('CorsBlankStack', {
      ALLOWED_ORIGINS: ' , , ',
    });

    template.allResourcesProperties('AWS::Lambda::Function', {
      Environment: {
        Variables: Match.objectLike({
          ALLOWED_ORIGINS: DEFAULT_ALLOWED_ORIGINS,
        }),
      },
    });
  });

  test('stage throttling configured', () => {
    const template = synthStack('ThrottleStack', defaultEnv);

    template.hasResourceProperties('AWS::ApiGateway::Stage', {
      MethodSettings: Match.arrayWith([
        Match.objectLike({ ThrottlingRateLimit: 50, ThrottlingBurstLimit: 100 }),
      ]),
    });
  });

  test('preflight responses are cached for one hour', () => {
    const template = synthStack('PreflightStack', defaultEnv);

    template.hasResourceProperties('AWS::ApiGateway::Method', {
      HttpMethod: 'OPTIONS',
      Integration: Match.objectLike({
        IntegrationResponses: Match.arrayWith([
          Match.objectLike({
            ResponseParameters: Match.objectLike({
              'method.response.header.Access-Control-Max-Age': "'3600'",
            }),
          }),
        ]),
      }),
    });
  });

  test('preflight uses the primary origin, allows localhost, and never a wildcard', () => {
    const template = synthStack('PreflightAllowlistStack', defaultEnv);

    const preflightMethods = template.findResources('AWS::ApiGateway::Method', {
      Properties: { HttpMethod: 'OPTIONS' },
    });
    const serialized = JSON.stringify(preflightMethods);

    expect(serialized).toContain("'https://oleksandrkobrynskyi.github.io'");
    expect(serialized).toContain('http://localhost:5173');
    expect(serialized).not.toContain('*');
  });

  test('request validator validates body', () => {
    const template = synthStack('ValidatorStack', defaultEnv);

    template.hasResourceProperties('AWS::ApiGateway::RequestValidator', {
      ValidateRequestBody: true,
    });
  });

  test('POST model requires an integer score from 0 to 1000 and bounded answers', () => {
    const template = synthStack('ModelStack', defaultEnv);

    template.hasResourceProperties('AWS::ApiGateway::Model', {
      Schema: Match.objectLike({
        properties: Match.objectLike({
          score: Match.objectLike({
            type: 'integer',
            minimum: 0,
            maximum: 1000,
          }),
          answers: Match.objectLike({
            type: 'array',
            maxItems: 200,
            items: Match.objectLike({
              type: 'string',
              minLength: 1,
              maxLength: 256,
            }),
          }),
        }),
      }),
    });
  });

  test('gateway responses for 4XX, 5XX and 413', () => {
    const template = synthStack('GatewayResponseStack', defaultEnv);

    for (const type of ['DEFAULT_4XX', 'DEFAULT_5XX', 'REQUEST_TOO_LARGE']) {
      template.hasResourceProperties('AWS::ApiGateway::GatewayResponse', {
        ResponseType: type,
        ResponseParameters: {
          'gatewayresponse.header.Access-Control-Allow-Origin':
            "'https://oleksandrkobrynskyi.github.io'",
          'gatewayresponse.header.Access-Control-Allow-Headers':
            "'Content-Type,X-Admin-Login,X-Admin-Password'",
          'gatewayresponse.header.Access-Control-Allow-Methods': "'OPTIONS,GET,POST'",
        },
      });
    }
  });

  test('lambda IAM policies are least privilege', () => {
    const template = synthStack('IamStack', defaultEnv);

    const policies = template.findResources('AWS::IAM::Policy');
    const statements: any[] = Object.values(policies).flatMap((policy: any) =>
      policy.Properties.PolicyDocument.Statement as any[]);
    const actions: string[] = statements.flatMap((statement: any) =>
      ([] as string[]).concat(statement.Action));

    expect(actions).toContain('dynamodb:PutItem');
    expect(actions).toContain('dynamodb:Query');
    expect(actions).toContain('dynamodb:Scan');
    expect(actions).not.toContain('dynamodb:UpdateItem');
    expect(actions).not.toContain('dynamodb:DeleteItem');
    expect(actions).not.toContain('dynamodb:GetItem');
    expect(actions).not.toContain('dynamodb:BatchWriteItem');
    expect(actions).not.toContain('dynamodb:DescribeTable');

    const putStatement = statements.find((statement: any) =>
      ([] as string[]).concat(statement.Action).includes('dynamodb:PutItem'));
    expect(([] as string[]).concat(putStatement.Action)).toEqual(['dynamodb:PutItem']);

    const readStatement = statements.find((statement: any) =>
      ([] as string[]).concat(statement.Action).includes('dynamodb:Scan'));
    expect(([] as string[]).concat(readStatement.Action))
      .toEqual(['dynamodb:Query', 'dynamodb:Scan']);
  });

  test('reserved concurrency is not set by default', () => {
    const template = synthStack('NoReservedStack', defaultEnv);

    const lambdas = template.findResources('AWS::Lambda::Function');
    for (const lambda of Object.values(lambdas)) {
      expect((lambda as any).Properties.ReservedConcurrentExecutions).toBeUndefined();
    }
  });

  test('reserved concurrency is applied only when explicitly configured', () => {
    const template = synthStack('ReservedStack', {
      ...defaultEnv,
      LAMBDA_RESERVED_CONCURRENCY: '5',
    });

    template.allResourcesProperties('AWS::Lambda::Function', {
      ReservedConcurrentExecutions: 5,
    });
  });

  test('outputs exist', () => {
    const template = synthStack('OutputsStack', defaultEnv);

    template.hasOutput('ApiEndpointUrl', {});
    template.hasOutput('DynamoDbTableName', {});
  });
});
