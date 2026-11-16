package io.jenkins.plugins.exportjobparameters;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.security.Permission;
import hudson.util.Secret;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.TransientActionFactory;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.structs.describable.DescribableParameter;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;

/**
 * Renders the parameters of a job as Pipeline code.
 */
public class ExportParamsAction implements Action {
    private static final Logger LOGGER = Logger.getLogger(ExportParamsAction.class.getName());

    private static final String INDENT = GroovyFormatter.INDENT;

    private final Job<?, ?> job;

    public ExportParamsAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    public Permission getPermission() {
        return Item.EXTENDED_READ;
    }

    @Override
    public String getIconFileName() {
        return job.hasPermission(getPermission()) ? "symbol-parameters" : null;
    }

    @Override
    public String getDisplayName() {
        return "Export Job Parameters";
    }

    @Override
    public String getUrlName() {
        return "export-parameters";
    }

    /**
     * @return the {@code parameters} directive of a Declarative Pipeline, or null if one of the
     *     parameters has no symbol and so cannot be used in a Declarative Pipeline
     */
    @CheckForNull
    public String getDeclarativeBlock() {
        List<String> snippets = snippets(INDENT);
        if (snippets.stream().anyMatch(s -> s.startsWith("["))) {
            return null;
        }
        return "parameters {\n" + INDENT + String.join("\n" + INDENT, snippets) + "\n}";
    }

    /**
     * @return the {@code properties} step of a Scripted Pipeline
     */
    @NonNull
    public String getScriptedBlock() {
        String indent = INDENT + INDENT;
        List<String> snippets = snippets(indent);
        int last = snippets.size() - 1;
        while (last >= 0 && isComment(snippets.get(last))) {
            last--;
        }
        StringBuilder block = new StringBuilder("properties([\n" + INDENT + "parameters([\n");
        for (int i = 0; i < snippets.size(); i++) {
            String snippet = snippets.get(i);
            block.append(indent).append(snippet).append(i < last && !isComment(snippet) ? ",\n" : "\n");
        }
        return block.append(INDENT).append("])\n])").toString();
    }

    private static boolean isComment(String snippet) {
        return snippet.startsWith("//");
    }

    private List<String> snippets(String indent) {
        List<String> snippets = new ArrayList<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return snippets;
        }
        for (ParameterDefinition definition : property.getParameterDefinitions()) {
            try {
                UninstantiatedDescribable ud = DescribableModel.uninstantiate2_(definition);
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = (Map<String, Object>) ud.getArguments();
                if (ud.getSymbol() == null) {
                    ud.setKlass(classNameOf(definition));
                }
                restoreShadowedType(definition, arguments);
                // Pipeline cannot set a secret from a string literal, and a secret does not belong in a Jenkinsfile
                if (arguments.values().stream()
                        .anyMatch(v ->
                                v instanceof Secret s && !Secret.toString(s).isEmpty())) {
                    snippets.add(
                            "// The default value of " + definition.getName() + " is a secret and is not exported");
                }
                arguments.values().removeIf(Secret.class::isInstance);
                // What Pipeline assumes anyway when the argument is missing
                arguments.values().removeIf(Objects::isNull);
                if (arguments.get("description") instanceof String description && description.isBlank()) {
                    arguments.remove("description");
                }
                snippets.add(GroovyFormatter.format(inDeclarationOrder(ud, true), indent));
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "Cannot export parameter " + definition.getName() + " of " + job.getFullName());
                snippets.add("// " + definition.getName() + " cannot be exported: "
                        + definition.getClass().getName() + " is not supported in Pipeline");
            }
        }
        return snippets;
    }

    /**
     * structs sorts the arguments by name. Put them back in the order the parameter type declares them, which starts
     * with the name, and put the description of the parameter last as the Pipeline documentation does.
     */
    private static Object inDeclarationOrder(Object value, boolean parameter) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(e -> inDeclarationOrder(e, false))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        if (!(value instanceof UninstantiatedDescribable ud)) {
            return value;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        DescribableModel<?> model = ud.getModel();
        if (model != null) {
            for (DescribableParameter declared : model.getParameters()) {
                if (ud.getArguments().containsKey(declared.getName())) {
                    arguments.put(declared.getName(), null);
                }
            }
        }
        ud.getArguments().forEach((name, argument) -> arguments.put(name, inDeclarationOrder(argument, false)));
        if (parameter && arguments.containsKey("description")) {
            arguments.put("description", arguments.remove("description"));
        }
        UninstantiatedDescribable ordered = new UninstantiatedDescribable(ud.getSymbol(), ud.getKlass(), arguments);
        ordered.setModel(model);
        return ordered;
    }

    /**
     * The simple class name is enough for {@code $class} unless two parameter types share it.
     */
    private static String classNameOf(ParameterDefinition definition) {
        Class<?> type = definition.getClass();
        long sameName = ParameterDefinition.all().stream()
                .filter(d -> d.clazz.getSimpleName().equals(type.getSimpleName()))
                .count();
        return sameName > 1 ? type.getName() : type.getSimpleName();
    }

    /**
     * A parameter whose constructor takes a {@code type} argument (Git Parameter does) is read by structs through
     * {@link ParameterDefinition#getType()}, which returns the class name instead. Read the field, as structs does
     * for a property without a getter.
     */
    private static void restoreShadowedType(ParameterDefinition definition, Map<String, Object> arguments) {
        if (!definition.getType().equals(arguments.get("type"))) {
            return;
        }
        for (Class<?> c = definition.getClass(); c != ParameterDefinition.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("type");
                field.setAccessible(true);
                if (field.get(definition) instanceof String value) {
                    arguments.put("type", value);
                }
                return;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            } catch (IllegalAccessException | InaccessibleObjectException e) {
                return;
            }
        }
    }

    @Extension
    public static class Factory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Job target) {
            Job<?, ?> job = target;
            ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
            if (property == null || property.getParameterDefinitions().isEmpty()) {
                return List.of();
            }
            return List.of(new ExportParamsAction(job));
        }
    }
}
