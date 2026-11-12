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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;

/**
 * Renders the parameters of a job as Pipeline code.
 */
public class ExportParamsAction implements Action {
    private static final Logger LOGGER = Logger.getLogger(ExportParamsAction.class.getName());

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
        List<String> snippets = snippets();
        if (snippets.stream().anyMatch(s -> s.startsWith("["))) {
            return null;
        }
        return "parameters {\n    " + String.join("\n    ", snippets) + "\n}";
    }

    /**
     * @return the {@code properties} step of a Scripted Pipeline
     */
    @NonNull
    public String getScriptedBlock() {
        return "properties([\n    parameters([\n        " + String.join(",\n        ", snippets()) + "\n    ])\n])";
    }

    private List<String> snippets() {
        List<String> snippets = new ArrayList<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return snippets;
        }
        for (ParameterDefinition definition : property.getParameterDefinitions()) {
            try {
                UninstantiatedDescribable ud = DescribableModel.uninstantiate2_(definition);
                if (ud.getSymbol() == null) {
                    ud.setKlass(definition.getClass().getSimpleName());
                }
                // Pipeline cannot set a secret from a string literal, and a secret does not belong in a Jenkinsfile
                Collection<?> values = ud.getArguments().values();
                if (values.stream()
                        .anyMatch(v ->
                                v instanceof Secret s && !Secret.toString(s).isEmpty())) {
                    snippets.add(
                            "// The default value of " + definition.getName() + " is a secret and is not exported");
                }
                values.removeIf(Secret.class::isInstance);
                snippets.add(Snippetizer.object2Groovy(ud, true));
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
