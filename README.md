# Export Job Parameters Plugin

## Description

This Jenkins plugin exports job parameters to declarative pipeline syntax. It helps users migrate their jobs to Pipeline by automatically generating the `parameters {}` block with all configured parameters, supporting both standard Jenkins parameters and custom parameter types.

## Usage

The plugin adds an "Export Job Parameters" action to any job that has parameters configured. When clicked, it generates a declarative pipeline parameters block that you can copy directly into your Jenkinsfile.

### Example

For a job with these parameters:
```groovy
// Original job parameters
- String parameter: DEPLOY_ENV
- Choice parameter: REGION
- Boolean parameter: DEBUG_MODE
```

The plugin will generate:
```groovy
parameters {
    string(name: 'DEPLOY_ENV', defaultValue: 'staging', description: 'Environment to deploy to')
    choice(name: 'REGION', choices: ['us-east-1', 'us-west-2', 'eu-west-1'], description: 'AWS Region')
    booleanParam(name: 'DEBUG_MODE', defaultValue: false, description: 'Enable debug logging')
}
```

## Issues

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) for information about how to get involved.

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
