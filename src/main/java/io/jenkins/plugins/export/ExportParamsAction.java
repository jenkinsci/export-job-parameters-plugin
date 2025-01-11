package io.jenkins.plugins.export;

import hudson.model.*;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class ExportParamsAction implements Action {
    private final Job<?, ?> job;
    private static final Map<Class<?>, ParameterFormatter> FORMATTERS = new HashMap<>();
    private static final Set<String> EXCLUDED_PROPERTIES = new HashSet<>(Arrays.asList(
        "class",              // Java internal
        "descriptor",         // Jenkins internal
        "type",              // Redundant with class name
        "formattedDescription", // Derived from description
        "defaultParameterValue", // Internal representation
        "defaultValueAsSecret"  // Internal state
    ));

    // Initialize the formatters map with known parameter types
    static {
        registerFormatter(StringParameterDefinition.class, (param, props) ->
                String.format("string(name: '%s', defaultValue: '%s', description: '%s')",
                        escapeGroovy(props.get("name")),
                        escapeGroovy(props.get("defaultValue")),
                        escapeGroovy(props.get("description")))
        );

        registerFormatter(BooleanParameterDefinition.class, (param, props) ->
                String.format("booleanParam(name: '%s', defaultValue: %s, description: '%s')",
                        escapeGroovy(props.get("name")),
                        props.get("defaultValue"),
                        escapeGroovy(props.get("description")))
        );

        registerFormatter(ChoiceParameterDefinition.class, (param, props) -> {
            List<String> choices = (List<String>) props.get("choices");
            String choicesStr = choices.stream()
                    .map(ExportParamsAction::escapeGroovy)
                    .collect(Collectors.joining("', '"));
            return String.format("choice(name: '%s', choices: ['%s'], description: '%s')",
                    escapeGroovy(props.get("name")),
                    choicesStr,
                    escapeGroovy(props.get("description")));
        });

        // Add support for Active Choices Parameter if available
        try {
            Class<?> cascadeChoiceClass = Class.forName("org.biouno.unochoice.CascadeChoiceParameter");
            registerFormatter(cascadeChoiceClass, (param, props) ->
                    String.format("activeChoice(name: '%s', script: '''%s''', description: '%s', choiceType: '%s')",
                            escapeGroovy(props.get("name")),
                            escapeGroovy(props.get("script").toString()),
                            escapeGroovy(props.get("description")),
                            props.get("choiceType"))
            );
        } catch (ClassNotFoundException e) {
            // Active Choices plugin not installed - ignore
        }
    }

    public ExportParamsAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    public String getIconFileName() {
        // Only show the action if the job has parameters and user has permission
        ParametersDefinitionProperty params = job.getProperty(ParametersDefinitionProperty.class);
        if (params == null || params.getParameterDefinitions().isEmpty()) {
            return null;
        }
        return Jenkins.get().hasPermission(Job.CONFIGURE) ? "clipboard.png" : null;
    }

    @Override
    public String getDisplayName() {
        return "Export Job Parameters";
    }

    @Override
    public String getUrlName() {
        return "export-parameters";
    }

    // Entrypoint
    public String generateGroovyParametersBlock() {
        ParametersDefinitionProperty paramsDefProperty = job.getProperty(ParametersDefinitionProperty.class);
        if (paramsDefProperty == null) {
            return "parameters {\n}\n";
        }

        List<ParameterDefinition> paramDefs = paramsDefProperty.getParameterDefinitions();
        boolean allHaveDeclarativeNames = true;
        
        // First pass - check if all params have declarative names
        for (ParameterDefinition paramDef : paramDefs) {
            if (getDeclarativeName(paramDef) == null) {
                allHaveDeclarativeNames = false;
                break;
            }
        }

        StringBuilder block = new StringBuilder();
        if (allHaveDeclarativeNames) {
            // Use parameters {} syntax
            block.append("parameters {\n");
        } else {
            // Use properties([parameters([])]) syntax
            block.append("properties([\n    parameters([\n");
        }

        for (ParameterDefinition paramDef : paramDefs) {
            String parameterBlock = generateParameterBlock(paramDef, !allHaveDeclarativeNames);
            if (parameterBlock != null) {
                block.append(allHaveDeclarativeNames ? "    " : "        ")
                     .append(parameterBlock)
                     .append(allHaveDeclarativeNames ? "\n" : ",\n");
            }
        }

        if (allHaveDeclarativeNames) {
            block.append("}\n");
        } else {
            block.append("    ])\n])\n");
        }
        
        return block.toString();
    }

    // Process param definition
    private String generateParameterBlock(ParameterDefinition paramDef, boolean useClassSyntax) {
        try {
            Map<String, Object> properties = extractProperties(paramDef);
            
            if (!useClassSyntax) {
                ParameterFormatter formatter = FORMATTERS.get(paramDef.getClass());
                if (formatter != null) {
                    return formatter.format(paramDef, properties);
                }
            }
            
            String declarativeName = getDeclarativeName(paramDef);
            if (declarativeName != null && !useClassSyntax) {
                return generateDeclarativeParameterBlock(paramDef, properties, declarativeName);
            } else {
                // Fall back to class-based syntax
                return generateClassBasedParameterBlock(paramDef, properties);
            }
        } catch (Exception e) {
            System.err.println("Error generating parameter block for " + paramDef.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private String generateDeclarativeParameterBlock(ParameterDefinition paramDef, Map<String, Object> properties, String declarativeName) {
        StringBuilder paramBlock = new StringBuilder();
        paramBlock.append(declarativeName).append("(");
        appendProperties(paramBlock, properties);
        paramBlock.append(")");
        return paramBlock.toString();
    }

    private String generateClassBasedParameterBlock(ParameterDefinition paramDef, Map<String, Object> properties) {
        StringBuilder paramBlock = new StringBuilder();
        paramBlock.append("[$class: '").append(paramDef.getClass().getSimpleName()).append("'");
        
        if (!properties.isEmpty()) {
            paramBlock.append(", ");
            appendProperties(paramBlock, properties);
        }
        
        paramBlock.append("]");
        return paramBlock.toString();
    }

    private void appendProperties(StringBuilder paramBlock, Map<String, Object> properties) {
        boolean first = true;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!first) {
                paramBlock.append(", ");
            }
            first = false;
            
            paramBlock.append(entry.getKey()).append(": ");
            Object value = entry.getValue();
            if (value instanceof String) {
                paramBlock.append("'").append(escapeGroovy((String)value)).append("'");
            } else if (value instanceof List) {
                paramBlock.append(formatList((List<?>) value));
            } else {
                paramBlock.append(value);
            }
        }
    }

    private Map<String, Object> extractProperties(ParameterDefinition paramDef) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Method[] methods = paramDef.getClass().getMethods();

        // Add common properties first
        properties.put("name", paramDef.getName());
        if (paramDef.getDescription() != null) {
            properties.put("description", paramDef.getDescription());
        }

        // Extract properties from getter methods, excluding known irrelevant ones
        for (Method method : methods) {
            if (isGetter(method)) {
                try {
                    String propertyName = getPropertyNameFromGetter(method.getName());
                    if (!EXCLUDED_PROPERTIES.contains(propertyName)) {
                        Object value = method.invoke(paramDef);
                        // Only include non-null values and exclude internal Jenkins objects
                        if (value != null && !isInternalJenkinsObject(value)) {
                            properties.put(propertyName, value);
                        }
                    }
                } catch (Exception e) {
                    // Skip properties that can't be accessed
                    continue;
                }
            }
        }

        return properties;
    }

    private String getDeclarativeName(ParameterDefinition paramDef) {
        Descriptor descriptor = paramDef.getDescriptor();
        if (descriptor != null) {
            // Check if @Symbol exists
            Symbol symbolAnnotation = descriptor.getClass().getAnnotation(Symbol.class);
            if (symbolAnnotation != null) {
                String[] symbols = symbolAnnotation.value();
                if (symbols.length > 0) {
                    return symbols[0];
                }
            }
        }
        return null;
    }

    private static boolean isGetter(Method method) {
        return (method.getName().startsWith("get") || method.getName().startsWith("is")) &&
                method.getParameterCount() == 0 &&
                !method.getReturnType().equals(void.class) &&
                !method.getName().equals("getClass");
    }

    private static String getPropertyNameFromGetter(String methodName) {
        if (methodName.startsWith("get")) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        } else if (methodName.startsWith("is")) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return methodName;
    }

    private boolean isInternalJenkinsObject(Object value) {
        String className = value.getClass().getName();
        return className.startsWith("hudson.") || 
               className.startsWith("jenkins.") || 
               className.startsWith("org.jenkinsci.");
    }

    private static String escapeGroovy(Object value) {
        if (value == null) return "";
        return value.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String formatList(List<?> list) {
        return "[" + list.stream()
                .map(item -> item instanceof String ? "'" + escapeGroovy((String)item) + "'" : item.toString())
                .collect(Collectors.joining(", ")) + "]";
    }

    public static void registerFormatter(Class<?> parameterClass, ParameterFormatter formatter) {
        FORMATTERS.put(parameterClass, formatter);
    }

    @FunctionalInterface
    public interface ParameterFormatter {
        String format(ParameterDefinition parameter, Map<String, Object> properties);
    }
}
