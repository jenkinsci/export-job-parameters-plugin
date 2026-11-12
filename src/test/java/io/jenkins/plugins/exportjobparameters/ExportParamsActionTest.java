package io.jenkins.plugins.exportjobparameters;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import hudson.util.Secret;
import java.net.HttpURLConnection;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.biouno.unochoice.CascadeChoiceParameter;
import org.biouno.unochoice.ChoiceParameter;
import org.biouno.unochoice.model.GroovyScript;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest2;

@WithJenkins
class ExportParamsActionTest {

    private static final String TRICKY = "it's a \"quoted\" \\ path with $HOME and ${braces}";
    private static final String MULTILINE = "first line\nit's '''tricky''' \\ $HOME\nlast line";

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
    }

    @Test
    void builtInParameters() throws Exception {
        FreeStyleProject job = job(
                new StringParameterDefinition("DEPLOY_ENV", "staging", "Environment to deploy to", true),
                new ChoiceParameterDefinition("REGION", new String[] {"us-east-1", "eu-west-1"}, "AWS region"),
                new BooleanParameterDefinition("DEBUG", true, null),
                new TextParameterDefinition("NOTES", "line1\nline2", "Release notes"),
                new PasswordParameterDefinition("TOKEN", Secret.fromString("s3cr3t"), "API token"));

        assertEquals("""
                parameters {
                    string(defaultValue: 'staging', description: 'Environment to deploy to', name: 'DEPLOY_ENV', trim: true)
                    choice(choices: ['us-east-1', 'eu-west-1'], description: 'AWS region', name: 'REGION')
                    booleanParam(defaultValue: true, name: 'DEBUG')
                    text(defaultValue: '''line1
                line2''', description: 'Release notes', name: 'NOTES')
                    // The default value of TOKEN is a secret and is not exported
                    password(description: 'API token', name: 'TOKEN')
                }""", action(job).getDeclarativeBlock());
        assertEquals("""
                properties([
                    parameters([
                        string(defaultValue: 'staging', description: 'Environment to deploy to', name: 'DEPLOY_ENV', trim: true),
                        choice(choices: ['us-east-1', 'eu-west-1'], description: 'AWS region', name: 'REGION'),
                        booleanParam(defaultValue: true, name: 'DEBUG'),
                        text(defaultValue: '''line1
                line2''', description: 'Release notes', name: 'NOTES'),
                        // The default value of TOKEN is a secret and is not exported,
                        password(description: 'API token', name: 'TOKEN')
                    ])
                ])""", action(job).getScriptedBlock());
    }

    @Test
    void declarativeBlockRecreatesTheParameters() throws Exception {
        FreeStyleProject original = job(roundTripParameters());

        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "declarative");
        pipeline.setDefinition(new CpsFlowDefinition(
                "pipeline {\n  agent none\n" + action(original).getDeclarativeBlock()
                        + "\n  stages { stage('s') { steps { echo 'ok' } } }\n}",
                true));
        j.buildAndAssertSuccess(pipeline);

        assertSameParameters(original, pipeline);
    }

    @Test
    void scriptedBlockRecreatesTheParameters() throws Exception {
        FreeStyleProject original = job(roundTripParameters());

        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "scripted");
        pipeline.setDefinition(new CpsFlowDefinition(action(original).getScriptedBlock() + "\necho 'ok'", true));
        j.buildAndAssertSuccess(pipeline);

        assertSameParameters(original, pipeline);
    }

    @Test
    void parameterWithoutSymbolIsOnlyExportedAsScripted() throws Exception {
        FreeStyleProject job = job(new StringParameterDefinition("A", "a"), new NoSymbolParameterDefinition("B"));

        assertNull(action(job).getDeclarativeBlock());
        assertEquals("""
                properties([
                    parameters([
                        string(defaultValue: 'a', name: 'A'),
                        [$class: 'NoSymbolParameterDefinition', name: 'B']
                    ])
                ])""", action(job).getScriptedBlock());
        assertThat(
                j.createWebClient().getPage(job, "export-parameters").asNormalizedText(),
                containsString("has no Pipeline symbol"));
    }

    @Test
    void parameterThatPipelineCannotInstantiateIsReported() throws Exception {
        FreeStyleProject job = job(new StringParameterDefinition("A", "a"), new UnsupportedParameterDefinition("B"));

        assertThat(action(job).getDeclarativeBlock(), containsString("string(defaultValue: 'a', name: 'A')\n"));
        assertThat(
                action(job).getDeclarativeBlock(),
                containsString("// B cannot be exported: " + UnsupportedParameterDefinition.class.getName()));
    }

    @Test
    void pageShowsBothBlocks() throws Exception {
        FreeStyleProject job = job(new StringParameterDefinition("NAME", "<b>'x'</b>"));

        HtmlPage page = j.createWebClient().getPage(job, "export-parameters");

        assertEquals(
                action(job).getDeclarativeBlock(),
                page.getElementById("export-parameters-declarative").getTextContent());
        assertEquals(
                action(job).getScriptedBlock(),
                page.getElementById("export-parameters-scripted").getTextContent());
        assertNotNull(j.createWebClient()
                .getPage(job)
                .getAnchorByHref(j.contextPath + "/" + job.getUrl() + "export-parameters"));
    }

    @Test
    void jobWithoutParametersHasNoAction() throws Exception {
        assertNull(j.createFreeStyleProject().getAction(ExportParamsAction.class));
    }

    @Test
    void pageRequiresExtendedRead() throws Exception {
        FreeStyleProject job = job(new StringParameterDefinition("NAME", "value"));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader", "configurer")
                .grant(Item.CONFIGURE)
                .everywhere()
                .to("configurer"));

        JenkinsRule.WebClient reader = j.createWebClient().login("reader");
        reader.assertFails(job.getUrl() + "export-parameters", HttpURLConnection.HTTP_FORBIDDEN);
        assertThat(reader.getPage(job).asXml(), not(containsString("export-parameters")));

        JenkinsRule.WebClient configurer = j.createWebClient().login("configurer");
        assertThat(
                configurer.getPage(job, "export-parameters").asNormalizedText(),
                containsString("string(defaultValue: 'value', name: 'NAME')"));
    }

    private ParameterDefinition[] roundTripParameters() throws Exception {
        ChoiceParameter environment = new ChoiceParameter(
                "ENVIRONMENT",
                "Target environment",
                "environment-choice",
                new GroovyScript(
                        new SecureGroovyScript("return ['dev', 'prod']", true, null),
                        new SecureGroovyScript("return ['error']", true, null)),
                ChoiceParameter.PARAMETER_TYPE_SINGLE_SELECT,
                true,
                3);
        CascadeChoiceParameter host = new CascadeChoiceParameter(
                "HOST",
                "Hosts of the environment",
                "host-choice",
                new GroovyScript(
                        new SecureGroovyScript(
                                "if (ENVIRONMENT == 'prod') {\n  return [\"p1\", \"p2\"]\n}\nreturn ['d1']",
                                true,
                                null),
                        null),
                CascadeChoiceParameter.PARAMETER_TYPE_CHECK_BOX,
                "ENVIRONMENT",
                false,
                1);
        return new ParameterDefinition[] {
            new StringParameterDefinition("STRING", TRICKY, TRICKY, true),
            new TextParameterDefinition("TEXT", MULTILINE, MULTILINE),
            new BooleanParameterDefinition("BOOLEAN", true, "A flag"),
            new ChoiceParameterDefinition("CHOICE", new String[] {"it's", "a \"b\"", "c\\d"}, null),
            new PasswordParameterDefinition("PASSWORD", Secret.fromString("s3cr3t"), "A secret"),
            environment,
            host
        };
    }

    private void assertSameParameters(Job<?, ?> expected, Job<?, ?> actual) {
        assertEquals(
                action(expected).getScriptedBlock().replaceFirst(" *// The default value of PASSWORD .*\\n", ""),
                action(actual).getScriptedBlock());

        ParametersDefinitionProperty property = actual.getProperty(ParametersDefinitionProperty.class);
        assertEquals(TRICKY, ((StringParameterDefinition) property.getParameterDefinition("STRING")).getDefaultValue());
        assertEquals(TRICKY, property.getParameterDefinition("STRING").getDescription());
        assertEquals(MULTILINE, ((TextParameterDefinition) property.getParameterDefinition("TEXT")).getDefaultValue());
        assertEquals(
                List.of("it's", "a \"b\"", "c\\d"),
                ((ChoiceParameterDefinition) property.getParameterDefinition("CHOICE")).getChoices());
        assertEquals(
                "if (ENVIRONMENT == 'prod') {\n  return [\"p1\", \"p2\"]\n}\nreturn ['d1']",
                ((GroovyScript) ((CascadeChoiceParameter) property.getParameterDefinition("HOST")).getScript())
                        .getScript()
                        .getScript());
    }

    private FreeStyleProject job(ParameterDefinition... definitions) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject();
        job.addProperty(new ParametersDefinitionProperty(definitions));
        return job;
    }

    private static ExportParamsAction action(Job<?, ?> job) {
        ExportParamsAction action = job.getAction(ExportParamsAction.class);
        assertNotNull(action);
        return action;
    }

    public static class NoSymbolParameterDefinition extends ParameterDefinition {

        @DataBoundConstructor
        public NoSymbolParameterDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return null;
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req) {
            return null;
        }

        @TestExtension
        public static class DescriptorImpl extends ParameterDescriptor {}
    }

    public static class UnsupportedParameterDefinition extends ParameterDefinition {

        public UnsupportedParameterDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return null;
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req) {
            return null;
        }

        @TestExtension
        public static class DescriptorImpl extends ParameterDescriptor {}
    }
}
