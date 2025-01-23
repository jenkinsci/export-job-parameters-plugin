package io.jenkins.plugins.exportjobparameters;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import jenkins.model.TransientActionFactory;
import java.util.Collection;
import java.util.Collections;

@Extension
public class ExportParamsActionFactory extends TransientActionFactory<Job> {
    
    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @Override
    public Collection<ExportParamsAction> createFor(Job target) {
        // Only create action if job has parameters
        if (target.getProperty(hudson.model.ParametersDefinitionProperty.class) != null) {
            return Collections.singleton(new ExportParamsAction(target));
        }
        return Collections.emptyList();
    }
}
