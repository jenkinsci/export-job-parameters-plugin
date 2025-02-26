# Instructions for the Export Job Parameters Plugin

## Plugin Summary

The **Export Job Parameters Plugin** is a Jenkins plugin designed to export job parameters into a Groovy block compatible with Jenkins pipelines. This facilitates easier migration, replication, and modification of job configurations by generating a `parameters` block that can be inserted directly into a Jenkinsfile.

The plugin inspects the job's parameter definitions and dynamically generates Groovy syntax using reflection and formatters for various parameter types. It supports both declarative pipeline syntax and scripted pipeline syntax, ensuring compatibility with diverse workflows.

## Purpose

1. **Simplify Migration**:
   - Extract job parameter configurations to make it easier to transition jobs to Jenkins pipelines.

2. **Enhance Reusability**:
   - Provide developers with reusable `parameters` blocks that can be copied into Jenkinsfiles.

3. **Support Extensibility**:
   - Allow easy addition of support for new parameter types.

4. **Ensure Compatibility**:
   - Generate both declarative and scripted pipeline configurations, depending on the job's parameter definitions.

## How the Plugin Works

### Key Components

1. **`ExportParamsAction` Class**:
   - Core business logic resides in this class.
   - Responsible for generating the Groovy block and providing UI integration.

2. **Parameter Formatters**:
   - Each parameter type has a formatter that generates the corresponding Groovy code.
   - Formatters for built-in parameters are predefined (e.g., `StringParameterDefinition`, `BooleanParameterDefinition`).
   - Additional formatters can be registered for custom parameter types.

3. **Reflection-Based Property Extraction**:
   - Uses reflection to extract parameter properties dynamically.
   - Supports extensibility by working with getter methods and excluding irrelevant properties.

4. **Groovy Syntax Escaping**:
   - Ensures special characters in parameter names, descriptions, and values are properly escaped for valid Groovy code.

### Workflow

1. **UI Integration**:
   - The plugin adds an action button labeled **"Export Job Parameters"** to job configuration pages.
   - This button is visible only if the job has parameters and the user has the required permissions.

2. **Parameter Extraction**:
   - Retrieves the `ParametersDefinitionProperty` from the job.
   - Iterates over all parameter definitions, extracting properties using reflection.

3. **Formatter Selection**:
   - Checks if the parameter has a formatter for declarative syntax.
   - If unavailable, falls back to generating class-based scripted syntax.

4. **Groovy Block Generation**:
   - Constructs the Groovy `parameters` block with either declarative or scripted syntax, depending on the parameters.

5. **Output**:
   - Returns the generated Groovy block as a string, which can be copied into a Jenkinsfile.

## Extending the Plugin

### Adding Support for a New Parameter Type

1. **Register a Formatter**:
   - Use the `registerFormatter` method to add a new formatter for the custom parameter type.
   - Example:
     ```java
     registerFormatter(CustomParameterDefinition.class, (param, props) ->
         String.format("customParam(name: '%s', customAttribute: '%s', description: '%s')",
             escapeGroovy(props.get("name")),
             escapeGroovy(props.get("customAttribute")),
             escapeGroovy(props.get("description")))
     );
     ```

2. **Handle Missing Formatters**:
   - For unsupported parameters, ensure graceful fallback to class-based syntax.

3. **Test the Formatter**:
   - Verify the generated Groovy block for various configurations of the new parameter type.

### Maintaining the Plugin

1. **Update Excluded Properties**:
   - Periodically review the `EXCLUDED_PROPERTIES` list to exclude irrelevant or internal properties of new parameter types.

2. **Improve Error Handling**:
   - Add meaningful log messages for exceptions during reflection or formatter processing.

3. **Optimize Performance**:
   - Consider caching symbols and extracted properties for commonly used parameter types.

4. **Document Changes**:
   - Maintain an updated list of supported parameter types and their corresponding formatters.

## Notes for Developers

- The plugin uses Java reflection extensively; ensure you test with a variety of parameter configurations.
- Declarative syntax is preferred, but fallback to scripted syntax ensures compatibility with all parameter types.
- Properly escape Groovy strings to avoid runtime syntax errors in generated blocks.
- Extend the plugin in a backward-compatible manner to avoid breaking existing jobs.

This document should help developers understand the plugin's purpose and architecture, making it easier to extend and maintain over time.

