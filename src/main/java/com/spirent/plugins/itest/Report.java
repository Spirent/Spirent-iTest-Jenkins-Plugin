/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, 
 * Martin Eigenbrodt, Peter Hayes
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.spirent.plugins.itest;

import hudson.FilePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Action;
import hudson.model.ProminentProjectAction;
import hudson.model.AbstractBuild;
import hudson.model.AbstractItem;
import hudson.model.AbstractProject;
import hudson.model.DirectoryBrowserSupport;
import hudson.model.Run;

import java.io.File;
import java.io.IOException;

import javax.servlet.ServletException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * A representation of an HTML directory to archive and publish.
 * 
 * @author Spirent
 * @since 1.0
 */
public class Report 
	extends AbstractDescribableImpl<Report> {
	
	/**
     * The name of the report to display for the build/project, such as 
     * "Code Coverage."
     */
    private final String reportName;

    /**
     * The path to the HTML report directory relative to the workspace.
     */
    private final String reportDir;

    /**
     * The file[s] to provide links inside the report directory.
     */
    private final String reportFiles;

    /**
     * If true, archive reports for all successful builds, otherwise only the 
     * most recent. Default to true for iTest plugin. 
     */
    private final boolean keepAll;

    /**
     * If true, will allow report to be missing and build will not fail on 
     * missing report. Default to true for iTest plugin. 
     */
    private final boolean allowMissing;

    /**
     * The name of the file which will be used as the wrapper index.
     */
    private final String wrapperName = "htmlpublisher-wrapper.html";

    @DataBoundConstructor
    public Report(String reportName, String reportDir, 
    		String reportFiles, boolean keepAll, boolean allowMissing) {
        this.reportName = reportName;
        this.reportDir = reportDir;
        this.reportFiles = reportFiles;
        this.keepAll = keepAll;
        this.allowMissing = allowMissing;
    }

    public String getReportName() {
        return this.reportName;
    }

    public String getReportDir() {
        return this.reportDir;
    }

    public String getReportFiles() {
        return this.reportFiles;
    }

    public boolean getKeepAll() {
        return this.keepAll;
    }

    public boolean getAllowMissing() {
           return this.allowMissing;
    }

    public String getSanitizedName() {
        String safeName = this.reportName;
        safeName = safeName.replace(" ", "_");
        return safeName;
    }

    public String getWrapperName() {
        return this.wrapperName;
    }

    public FilePath getArchiveTarget(AbstractBuild build) {
        return new FilePath(this.keepAll ? getBuildArchiveDir(build) : 
        	getProjectArchiveDir(build.getProject()));
    }

    /**
     * Gets the directory where the HTML report is stored for the given project.
     */
    private File getProjectArchiveDir(AbstractItem project) {
        return new File(new File(project.getRootDir(), "htmlreports"), 
        		this.getSanitizedName());
    }
    /**
     * Gets the directory where the HTML report is stored for the given build.
     */
    private File getBuildArchiveDir(Run run) {
        return new File(new File(run.getRootDir(), "htmlreports"), 
        		this.getSanitizedName());
    }

    protected abstract class BaseHTMLAction implements Action {
        private Report actualHtmlPublisherTarget;

        public BaseHTMLAction(Report actualHtmlPublisherTarget) {
            this.actualHtmlPublisherTarget = actualHtmlPublisherTarget;
        }

        public String getUrlName() {
            return actualHtmlPublisherTarget.getSanitizedName();
        }

        public String getDisplayName() {
            String action = actualHtmlPublisherTarget.reportName;
            return dir().exists() ? action : null;
        }

        public String getIconFileName() {
        	return "/plugin/itest/images/24x24/Spirent_favicon.ico";
        }

        /**
         * Serves HTML reports.
         */
        public void doDynamic(StaplerRequest req, StaplerResponse rsp) 
        		throws IOException, ServletException {
            DirectoryBrowserSupport dbs = new DirectoryBrowserSupport(
            		this, new FilePath(this.dir()), this.getTitle(), 
            		"/plugin/itest/images/24x24/Spirent_favicon.ico", false);
         // Hudson >= 1.312
            dbs.setIndexFileName(Report.this.wrapperName); 
            dbs.generateResponse(req, rsp, this);
        }

        protected abstract String getTitle();

        protected abstract File dir();
    }

    public class HTMLAction extends BaseHTMLAction 
    	implements ProminentProjectAction {
    	
        private final AbstractItem project;

        public HTMLAction(AbstractItem project,
        		Report actualHtmlPublisherTarget) {
            super(actualHtmlPublisherTarget);
            this.project = project;
        }

        @Override
        protected File dir() {
            if (this.project instanceof AbstractProject) {
                AbstractProject abstractProject = 
                		(AbstractProject) this.project;

                Run run = abstractProject.getLastSuccessfulBuild();
                if (run != null) {
                    File javadocDir = getBuildArchiveDir(run);

                    if (javadocDir.exists()) {
                        return javadocDir;
                    }
                }
            }

            return getProjectArchiveDir(this.project);
        }

        @Override
        protected String getTitle() {
            return this.project.getDisplayName() + " html2";
        }
    }

    public class HTMLBuildAction extends BaseHTMLAction {
        private final AbstractBuild<?, ?> build;

        public HTMLBuildAction(AbstractBuild<?, ?> build, 
        		Report actualHtmlPublisherTarget) {
            super(actualHtmlPublisherTarget);
            this.build = build;
        }
        
        public final AbstractBuild<?,?> getOwner() {
        	return build;
        }

        @Override
        protected String getTitle() {
            return this.build.getDisplayName() + " html3";
        }

        @Override
        protected File dir() {
            return getBuildArchiveDir(this.build);
        }
    }

    public void handleAction(AbstractBuild<?, ?> build) {
        // Add build action, if coverage is recorded for each build
        if (this.keepAll) {
            build.addAction(new HTMLBuildAction(build, this));
        }
    }

    public Action getProjectAction(AbstractProject project) {
        return new HTMLAction(project, this);
    }
}