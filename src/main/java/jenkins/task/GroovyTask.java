/**
 * Copyright (c) 2018 MicroNova AG
 * All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * <p>
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * <p>
 * 2. Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 * <p>
 * 3. Neither the name of MicroNova AG nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jenkins.task;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractProject;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.internal.ClientRequest;
import jenkins.internal.Util;
import jenkins.internal.data.ApiVersion;
import jenkins.internal.data.GroovyConfiguration;
import jenkins.internal.data.ModelConfiguration;
import jenkins.internal.descriptor.ExamModelDescriptor;
import jenkins.model.Jenkins;
import jenkins.plugins.exam.ExamTool;
import jenkins.plugins.exam.config.ExamModelConfig;
import jenkins.plugins.exam.config.ExamPluginConfig;
import jenkins.task._exam.ExamConsoleAnnotator;
import jenkins.task._exam.ExamConsoleErrorOut;
import jenkins.task._exam.Messages;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GroovyTask extends Builder implements SimpleBuildStep {

    /**
     * JAVA_OPTS if not null
     */
    private String javaOpts;

    /**
     * timeout if not null
     */
    private int timeout;

    /**
     * the script, and if specified the startElement, as ID, UUID, or FullScopedName
     */
    private String script;
    private String startElement;
    private boolean useStartElement;

    /**
     * the modelConfiguration as ID, UUID, or FullScopedName
     */
    private String modelConfiguration;

    /**
     * Identifies {@link jenkins.plugins.exam.config.ExamModelConfig} to be used.
     */
    private String examModel;

    /**
     * Identifies {@link ExamTool} to be used.
     */
    private String examName;

    @DataBoundConstructor
    public GroovyTask(String script, String startElement, String examName, String examModel, String modelConfiguration) {
        this.script = script;
        this.startElement = startElement;
        this.examName = examName;
        this.examModel = examModel;
        this.modelConfiguration = modelConfiguration;
    }

    public String getJavaOpts() {
        return javaOpts;
    }

    @DataBoundSetter
    public void setJavaOpts(String javaOpts) {
        this.javaOpts = javaOpts;
    }

    public int getTimeout() {
        return timeout;
    }

    @DataBoundSetter
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getStartElement() {
        return startElement;
    }

    @DataBoundSetter
    public void setUseStartElement(boolean useStartElement) {
        this.useStartElement = useStartElement;
    }

    public boolean isUseStartElement() {
        return useStartElement;
    }

    @DataBoundSetter
    public void setStartElement(String startElement) {
        this.startElement = startElement;
    }

    public String getScript() {
        return script;
    }

    @DataBoundSetter
    public void setScript(String script) {
        this.script = script;
    }

    public String getModelConfiguration() {
        return modelConfiguration;
    }

    @DataBoundSetter
    public void setModelConfiguration(String modelConfiguration) {
        this.modelConfiguration = modelConfiguration;
    }

    public String getExamModel() {
        return examModel;
    }

    @DataBoundSetter
    public void setExamModel(String examModel) {
        this.examModel = examModel;
    }

    public String getExamName() {
        return examName;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        // prepare environment
        ArgumentListBuilder args = new ArgumentListBuilder();

        ExamTaskHelper taskHelper = new ExamTaskHelper(run, workspace, launcher, taskListener);
        ExamTool tool = taskHelper.getTool(getExam());

        // prepare workspace
        FilePath buildFilePath = taskHelper.prepareWorkspace(tool, args);

        Jenkins instanceOrNull = Jenkins.getInstanceOrNull();
        assert instanceOrNull != null;
        ExamPluginConfig examPluginConfig = instanceOrNull.getDescriptorByType(
                ExamPluginConfig.class);
        args = taskHelper.handleAdditionalArgs(javaOpts, args, examPluginConfig);

        if (timeout <= 0) {
            timeout = examPluginConfig.getTimeout();
        }

        long startTime = System.currentTimeMillis();
        try {
            ClientRequest clientRequest = new ClientRequest(taskListener.getLogger(),
                    examPluginConfig.getPort(), launcher);
            Proc proc = null;
            Executor runExecutor = run.getExecutor();
            if (runExecutor != null) {
                if (clientRequest.isApiAvailable()) {
                    taskListener.getLogger().println("ERROR: EXAM is already running");
                    run.setResult(Result.FAILURE);
                    throw new AbortException("ERROR: EXAM is already running");
                }
                try (ExamConsoleAnnotator eca = new ExamConsoleAnnotator(taskListener.getLogger(),
                        run.getCharset());
                     ExamConsoleErrorOut examErr = new ExamConsoleErrorOut(
                             taskListener.getLogger())) {
                    Util.checkMinRestApiVersion(new ApiVersion(1, 1, 0), clientRequest);
                    Launcher.ProcStarter process = launcher.launch().cmds(args).envs(
                            taskHelper.getEnv()).pwd(
                            buildFilePath.getParent());
                    process.stderr(examErr).stdout(eca);
                    proc = process.start();

                    runGroovyScript(taskListener, clientRequest, runExecutor);
                } catch (IOException e) {
                    run.setResult(Result.FAILURE);
                    throw new AbortException("ERROR: " + e.toString());
                } finally {
                    try {
                        clientRequest.disconnectClient(runExecutor, timeout);
                    } finally {
                        if (proc != null && proc.isAlive()) {
                            proc.joinWithTimeout(10, TimeUnit.SECONDS, taskListener);
                        }
                    }
                }
            }
            run.setResult(Result.SUCCESS);
        } catch (IOException e) {
            taskHelper.handleIOException(startTime, e, getDescriptor().getInstallations());
        } finally {
            Result result = run.getResult();
            if (result != null) {
                taskListener.getLogger().println(result.toString());
            }
        }
    }

    private void runGroovyScript(@Nonnull TaskListener listener, ClientRequest clientRequest, Executor runExecutor) throws IOException, InterruptedException {
        boolean connected = clientRequest.connectClient(runExecutor, timeout);
        if (connected) {
            ApiVersion apiVersion = clientRequest.getApiVersion();
            if (apiVersion != null && !(apiVersion.getMajor() >= 1 && apiVersion.getMinor() >= 1)) {
                throw new AbortException("Api does not support Groovy Script execution");
            }
            String sApiVersion = (apiVersion == null) ? "unknown" : apiVersion.toString();
            listener.getLogger().println("EXAM api version: " + sApiVersion);

            ModelConfiguration modelConfig = createModelConfig();
            GroovyConfiguration config = createGroovyConfig();

            clientRequest.clearWorkspace(null);
            clientRequest.createExamProject(modelConfig);
            clientRequest.executeGoovyScript(config);
        }
    }

    private ModelConfiguration createModelConfig() throws AbortException {
        ModelConfiguration mc = new ModelConfiguration();
        ExamModelConfig m = getModel(examModel);
        if (m == null) {
            throw new AbortException("ERROR: no model configured with name: " + examModel);
        }
        mc.setProjectName(m.getName());
        mc.setModelName(m.getModelName());
        mc.setTargetEndpoint(m.getTargetEndpoint());
        mc.setModelConfigUUID(modelConfiguration);

        return mc;
    }

    private GroovyConfiguration createGroovyConfig() {
        GroovyConfiguration config = new GroovyConfiguration();
        config.setScript(getScript());
        if (isUseStartElement()) {
            config.setStartElement(getStartElement());
        } else {
            config.setStartElement("");
        }

        return config;
    }

    @Nullable
    private ExamTool getExam() {
        for (ExamTool tool : getDescriptor().getInstallations()) {
            if (examName != null && examName.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    @Nullable
    private ExamModelConfig getModel(String name) {
        for (ExamModelConfig mConfig : getDescriptor().getModelConfigs()) {
            if (mConfig.getName().equalsIgnoreCase(name)) {
                return mConfig;
            }
        }
        return null;
    }

    @Override
    public GroovyTask.DescriptorGroovyTask getDescriptor() {
        return (GroovyTask.DescriptorGroovyTask) super.getDescriptor();
    }

    @Extension
    @Symbol("examRun_Groovy")
    public static class DescriptorGroovyTask extends BuildStepDescriptor<Builder> implements ExamModelDescriptor, Serializable {

        private static final long serialVersionUID = 4277406576918447167L;

        @Nonnull
        public String getDisplayName() {
            return Messages.EXAM_RunGroovyTask();
        }

        public DescriptorGroovyTask() {
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public ExamTool[] getInstallations() {
            Jenkins instanceOrNull = Jenkins.getInstanceOrNull();
            return (instanceOrNull == null) ? new ExamTool[0] : instanceOrNull.getDescriptorByType(
                    ExamTool.DescriptorImpl.class)
                    .getInstallations();
        }

        public List<ExamModelConfig> getModelConfigs() {
            Jenkins instanceOrNull = Jenkins.getInstanceOrNull();
            if (instanceOrNull == null) {
                return new ArrayList<>();
            }
            return instanceOrNull.getDescriptorByType(ExamPluginConfig.class).getModelConfigs();
        }

        public ListBoxModel doFillExamNameItems() {
            ListBoxModel items = new ListBoxModel();
            ExamTool[] examTools = getInstallations();

            Arrays.sort(examTools,
                    (ExamTool o1, ExamTool o2) -> o1.getName().compareToIgnoreCase(o2.getName()));
            for (ExamTool tool : examTools) {
                items.add(tool.getName(), tool.getName());
            }
            return items;
        }

        public ListBoxModel doFillExamModelItems() {
            ListBoxModel items = new ListBoxModel();
            List<ExamModelConfig> models = getModelConfigs();
            models.sort(
                    (ExamModelConfig o1, ExamModelConfig o2) -> o1.getName().compareToIgnoreCase(
                            o2.getName()));

            for (ExamModelConfig model : models) {
                items.add(model.getDisplayName(), model.getName());
            }
            return items;
        }

        public FormValidation doCheckModelConfiguration(@QueryParameter String value) {
            return Util.validateElementForSearch(value);
        }

        public FormValidation doCheckScript(@QueryParameter String value) {
            return Util.validateElementForSearch(value);
        }

        public FormValidation doCheckStartElement(@QueryParameter String value) {
            return Util.validateElementForSearch(value);
        }
    }
}
