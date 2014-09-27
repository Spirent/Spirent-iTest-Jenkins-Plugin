/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2014 Spirent Communications, Inc.
 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.”
 */

package com.spirent.plugins.itest;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Parse test execution options for iTestCLI and iTestRT. 
 * 
 * @author Spirent 
 * @since 1.0
 */
public class ITest extends CommandInterpreter { 

	/**
	 * @since 1.0
	 */
	public final String workspace; 
	/**
	 * @since 1.0
	 */
	public final String projects; 
	/**
	 * @since 1.0
	 */
	public final String testcases; 
	/**
	 * @since 1.0
	 */
	public final String testbed;  
	/**
	 * @since 1.0
	 */
	public final String params;
	/**
	 * @since 1.0
	 */
	public final String paramFile; 
	/**
	 * @since 1.0
	 */
	public final boolean testReportRequired; 
	/**
	 * @since 1.0
	 */
	public final String dbCustomTag; 

	private final static boolean BUILD_FAILURE = false; 
	private final static boolean BUILD_SUCCESS = true; 

	private transient String safeTestbed = ""; 
	private transient String safeParamFile = ""; 

	private transient String iTestCommand = ""; 
	private transient String itestcli = ""; 
	private transient String itestrt = ""; 
	private transient ArrayList<String> testCaseNames; 

	@DataBoundConstructor
	public ITest(String workspace, String projects,
			String testcases, String testbed, String params, String paramFile,
			boolean testReportRequired, String dbCustomTag) {
		super(null);
		this.workspace = workspace;
		this.projects = projects;
		this.testcases = testcases;
		this.testbed = testbed;
		this.params = params;
		this.paramFile = paramFile;
		this.testReportRequired = testReportRequired;
		this.dbCustomTag = dbCustomTag;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) {

		ITest.Descriptor global = new ITest.Descriptor();  
		testCaseNames = new ArrayList<String>(); 
		itestcli = global.cliPath.isEmpty() ? "itestcli" : global.cliPath; 
		itestrt = global.rtPath.isEmpty() ? "itestrt" : global.rtPath; 

		processBuildWorkspace(build); 

		if (!canGenerateITARFile(projects.replaceAll("\\s+",""), 
				build, launcher, listener)) { 
			return BUILD_FAILURE; 
		}

		String licenseServerURI = global.lsIPAddress; 
		if(!global.lsPort.isEmpty()) { 
			licenseServerURI += ":" + global.lsPort; 
		}

		iTestCommand = itestrt + " --licenseServer " + licenseServerURI 
				+ " --itar " + parseWorkspace(build); 

		addTestExecutionOptions(); 
		parseTestCases(build); 

		if (!testReportRequired) { 
			if (!buildSucceeds(iTestCommand, build, launcher, listener) 
					|| !testPassed(build)) { 
				return BUILD_FAILURE; 
			}
		} else { 
			if (!canInitializeReport(build, launcher, listener) 
					|| !buildSucceeds(iTestCommand, build, launcher, listener)
					|| !canFinalizeReport(build, launcher, listener)
					|| !testPassed(build)) { 
				return BUILD_FAILURE; 
			}
		}

		return BUILD_SUCCESS; 
	}

	/**
	 * Expand environment variables for ${WORKSPACE}. 
	 * @param build
	 * @param src 
	 */
	private void processBuildWorkspace(AbstractBuild<?, ?> build) {

		String front = build.getWorkspace() + ""; 

		if (testbed.toUpperCase().contains("WORKSPACE")) { 
			String back = testbed.substring(testbed.indexOf("}") + 1); 
			safeTestbed = front + back; 
		} else { 
			safeTestbed = testbed; 
		}

		if (paramFile.toUpperCase().contains("WORKSPACE")) { 
			String back = paramFile.substring(paramFile.indexOf("}") + 1); 
			safeParamFile = front + back; 
		} else { 
			safeParamFile = paramFile; 
		}
	}

	/**
	 * Run iTestRT commands. 
	 * 
	 * @param command
	 * @param build
	 * @param launcher
	 * @param listener
	 */
	private boolean buildSucceeds(final String command, final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

		String uniformPathSeparators = command.replaceAll("\\\\", "/"); 
		CommandInterpreter runner = 
				getCommandInterpreter(launcher, uniformPathSeparators); 
		try {
			runner.perform(build, launcher, listener);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		return consoleOutputIsValid(build); 
	}

	/**
	 * Determine if relative or absolute workspace path was provided. 
	 * @param build 
	 */
	private String parseWorkspace(final AbstractBuild<?, ?> build) { 

		String front = build.getWorkspace() + ""; 

		if (workspace.toUpperCase().contains("WORKSPACE")) { 
			String back = workspace.substring(workspace.indexOf("}") + 1); 
			return front + back; 
		} 
		
		String regex = "[([A-Z]:\\\\)|/].*";
		if (Pattern.matches(regex, workspace)) { 
			return workspace; //full path was provided - Windows or *nix
		} else if (workspace.isEmpty()) { 
			//TODO these last two can be combined 
			return build.getWorkspace() + ""; //relative path 
		} else { 
			//workspace is a relative path inside the Jenkins workspace 
			return build.getWorkspace() + workspace; 
		}
	}

	private String expandEnvironmentVariables(String src){
		String res = src;
		Map<String, String> env = System.getenv();
		for(String key : env.keySet()){
			res = res.replaceAll("\\$"+key, env.get(key));
		}
		return res;
	}

	/**
	 * Prepare workspace to generate Spirent iTest test reports. 
	 * @param build
	 * @param launcher
	 * @param listener
	 * @return true if successful 
	 */
	private boolean canInitializeReport(final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

		FilePath test = build.getWorkspace();
		String test2 = "" + test; //convert FilePath to String 
		String safeWorkspacePath = expandEnvironmentVariables(
				test2.replaceAll("\\\\", "/")); 
		String buildID = build.getId(); //to set up build folders 

		//requires "resources" project ITAR 
		if (!canGenerateITARFile("resources", build, launcher, listener)) { 
			return BUILD_FAILURE; 
		}

		//create directory to store report (no harm if already exists) 
		String createTestReportDir = "pushd . & cd " + safeWorkspacePath 
				+ " & mkdir jenkins_test_reports_" + buildID + " & popd";

		if (!buildSucceeds(createTestReportDir, build, launcher, listener)){ 
			return BUILD_FAILURE; 
		}

		try {
			iTestCommand += " --report " + test.toURI()
					+ "jenkins_test_reports_" + buildID 
					+ "/{tcfilename}.html";
		} catch (Exception e) {
			e.printStackTrace();
			return BUILD_FAILURE; 
		} 

		addTestReportDatabaseOptions(); 
		return BUILD_SUCCESS; 
	}

	/**
	 * Publish HTML reports in Jenkins. 
	 * @param build
	 * @param launcher
	 * @param listener
	 * @return true if successful
	 */
	private boolean canFinalizeReport(final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

		FilePath test = build.getWorkspace();
		String test2 = "" + test; //convert FilePath to String 
		String safeWorkspacePath = test2.replaceAll("\\\\", "/"); 
		String buildID = build.getId(); //to set up build folders 
		String reportName = "Spirent iTest Report"; //name of display link 
		String reportDir = safeWorkspacePath  
				+ "/jenkins_test_reports_" + buildID; //one dir per build 
		List<Report> list = 
				new ArrayList<Report>(); 

		for (String s : testCaseNames) { 
			String temp = s.substring(s.lastIndexOf("/") + 1, 
					s.lastIndexOf(".")); 
			String report = reportName + "-" + temp; 
			list.add(new Report(report, reportDir, 
					temp + ".html", true, true)); 
		}

		ReportPublisher publisher = new ReportPublisher(list);

		try { 
			publisher.perform(build, launcher, listener); 
		} catch (InterruptedException e) {
			e.printStackTrace();
			return BUILD_FAILURE; 
		} 

		return BUILD_SUCCESS; 
	}

	/**
	 * Check console output for any error messages before proceeding. 
	 * @param build
	 * @param launcher
	 * @param listener
	 */
	private boolean consoleOutputIsValid(final AbstractBuild<?, ?> build) {  
		File test = build.getLogFile();
		Scanner scanner = null;
		try {
			scanner = new Scanner(test);
			while (scanner.hasNextLine()) { 
				String nextLine = scanner.nextLine(); 
				//error messages generated by iTestCLI and iTestRT 
				if (nextLine.contains("Error") 
						|| nextLine.contains("cannot find the path")
						|| nextLine.contains("valid directory")
						|| nextLine.contains("No project to be exported")
						|| nextLine.contains("Failed to generate report")) { 
					return false; 
				}
			} 
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} finally { 
			scanner.close();
		}

		return true; 
	}

	/**
	 * Determine if all any test cases have failed. 
	 * @param build
	 * @return
	 */
	private boolean testPassed(final AbstractBuild<?, ?> build) {  
		File test = build.getLogFile();
		Scanner scanner = null;
		try {
			scanner = new Scanner(test);
			while (scanner.hasNextLine()) { 
				String nextLine = scanner.nextLine(); 
				//error messages generated by iTestCLI and iTestRT 
				if (nextLine.contains("Execution status:  Fail")) { 
					return false; 
				}
			} 
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} finally { 
			scanner.close();
		}
		return true; 
	}

	/**
	 * Generate iTAR files using iTestCLI. 
	 * 
	 * @param project
	 * @param build
	 * @param launcher
	 * @param listener
	 */
	private boolean canGenerateITARFile(final String project, 
			final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

		//--exportProject accepts multiple projects separated 
		//by comma but not spaces 

		String path = parseWorkspace(build); 
		String generateITAR = itestcli + " --workspace " + path
				+ " --exportPath " + path + " --exportProject " 
				+ project.replaceAll("\\s+",""); 

		CommandInterpreter runner = 
				getCommandInterpreter(launcher, generateITAR); 

		try { 
			runner.perform(build, launcher, listener);
		} catch (Exception e) { 
			e.printStackTrace();
		}

		return consoleOutputIsValid(build); 
	}

	/**
	 * Each test case must be preceded by --test option. 
	 */
	private void parseTestCases(final AbstractBuild<?, ?> build) { 

		String[] multipleTestCases = testcases.replaceAll("\\s+","").split(","); 
		String back = null, temp = null; 

		for (String testCase: multipleTestCases) {
			if (testCase.toUpperCase().contains("WORKSPACE")) { 
				System.out.println("contains workspace");
				back = testCase.substring(testCase.indexOf("}") + 1);
				if (back.indexOf("/") == 0 || back.indexOf("\\") == 0) { 
					System.out.println("Slash was found at beginning");
					temp = "project://" + back.substring(1); 
				} else { 

					temp = "project://" + back; 
				}
			} else { 
				//TODO assumes that test case URI starts with project://
				temp = testCase; 
			}
			testCaseNames.add(new String(temp)); 
			iTestCommand += " --test " + temp; 
		}
	}

	/**
	 * Parse test execution options. 
	 */
	private void addTestExecutionOptions() { 
		if (!testbed.isEmpty()) { 
			iTestCommand += " --testbed file:/" + safeTestbed; 
		}

		if (!params.isEmpty()) { 
			String[] multipleParams = params.replaceAll("\\s+","").split(","); 
			for(String param : multipleParams) { 
				iTestCommand += " --param " + param; 
			}
		}

		if (!paramFile.isEmpty()) { 
			iTestCommand += " --paramfile file:/" + safeParamFile; 
		}
	}

	/**
	 * Parse test report database options. 
	 */
	private void addTestReportDatabaseOptions() { 

		//to access static nested class fields
		ITest.Descriptor global = new Descriptor();   

		if (!global.dbUsername.isEmpty()) { 
			iTestCommand += " --trdb.user " + global.dbUsername; 
			iTestCommand += " --trdb.password " + global.dbPassword; 

			if(!dbCustomTag.isEmpty()) { 
				iTestCommand += " --tag " + dbCustomTag; 
			}

			iTestCommand += " --host " + global.lsIPAddress; 

			if(!global.dbURI.isEmpty()) { 
				iTestCommand += " --uri " + global.dbURI; 
				return; 
			}

			iTestCommand += " --catalog " + global.dbName; 
			iTestCommand += " --dbtype " + global.dbType; 
			iTestCommand += " --ipaddr " + global.dbIPAddress; 
			iTestCommand += " --trdb.port " + global.dbPort; 
		}
	}

	/**
	 * Return correct CommandInterpreter based on OS  
	 * 
	 * @param launcher
	 * @param script
	 * @return CommandInterpreter
	 */
	private CommandInterpreter getCommandInterpreter(final Launcher launcher,
			final String script) {
		if (launcher.isUnix())
			return new Shell(script);
		else
			return new BatchFile(script);
	}

	@Extension
	public static final class Descriptor extends BuildStepDescriptor<Builder> {

		/**
		 * @since 1.0
		 */
		private String cliPath;
		/**
		 * @since 1.0
		 */
		private String rtPath; 
		/**
		 * @since 1.0
		 */
		private String lsIPAddress; 
		/**
		 * @since 1.0
		 */
		private String lsPort; 
		/**
		 * @since 1.0
		 */
		private String dbName;
		/**
		 * @since 1.0
		 */
		private String dbType; 
		/**
		 * @since 1.0
		 */
		private String dbUsername;
		/**
		 * @since 1.0
		 */
		private String dbPassword;
		/**
		 * @since 1.0
		 */
		private String dbURI;
		/**
		 * @since 1.0
		 */
		private String dbIPAddress;
		/**
		 * @since 1.0
		 */
		private String dbPort;

		/**
		 * @return the cliPath
		 */
		public String getCliPath() {
			return cliPath;
		}

		/**
		 * @return the rtPath
		 */
		public String getRtPath() {
			return rtPath;
		}

		/**
		 * @return the license server ip or host 
		 */
		public String getLsIPAddress() {
			return lsIPAddress;
		}

		/**
		 * @return the license server port number 
		 */
		public String getLsPort() { 
			return lsPort; 
		}

		/**
		 * @return the dbName
		 */
		public String getDbName() {
			return dbName;
		}

		/**
		 * @return the dbType
		 */
		public String getDbType() {
			return dbType;
		}

		/**
		 * Set dbType. 
		 * @param type
		 */
		public void setDbType(String type){ 
			dbType = type; 
		}

		/**
		 * @return the dbUsername
		 */
		public String getDbUsername() {
			return dbUsername;
		}

		/**
		 * @return the dbPassword
		 */
		public String getDbPassword() {
			return dbPassword;
		}

		/**
		 * @return the dbURI
		 */
		public String getDbURI() {
			return dbURI;
		}

		/**
		 * @return the dbIPAddress
		 */
		public String getDbIPAddress() {
			return dbIPAddress;
		}

		/**
		 * @return the dbPort
		 */
		public String getDbPort() {
			return dbPort;
		}

		/**
		 * @param cliPath the cliPath to set
		 */
		public void setCliPath(String cliPath) {
			this.cliPath = cliPath;
		}

		/**
		 * @param rtPath the rtPath to set
		 */
		public void setRtPath(String rtPath) {
			this.rtPath = rtPath;
		}

		/**
		 * @param lsIPAddress the lsIPAddress to set
		 */
		public void setLsIPAddress(String lsIPAddress) {
			this.lsIPAddress = lsIPAddress;
		}

		/**
		 * @param lsPort the lsPort to set
		 */
		public void setLsPort(String lsPort) {
			this.lsPort = lsPort;
		}

		/**
		 * @param dbName the dbName to set
		 */
		public void setDbName(String dbName) {
			this.dbName = dbName;
		}

		/**
		 * @param dbUsername the dbUsername to set
		 */
		public void setDbUsername(String dbUsername) {
			this.dbUsername = dbUsername;
		}

		/**
		 * @param dbPassword the dbPassword to set
		 */
		public void setDbPassword(String dbPassword) {
			this.dbPassword = dbPassword;
		}

		/**
		 * @param dbURI the dbURI to set
		 */
		public void setDbURI(String dbURI) {
			this.dbURI = dbURI;
		}

		/**
		 * @param dbIPAddress the dbIPAddress to set
		 */
		public void setDbIPAddress(String dbIPAddress) {
			this.dbIPAddress = dbIPAddress;
		}

		/**
		 * @param dbPort the dbPort to set
		 */
		public void setDbPort(String dbPort) {
			this.dbPort = dbPort;
		}

		@Override
		public String getDisplayName() {
			return "Execute Spirent iTest test case"; 
		}

		public Descriptor() {
			load();
		}

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true; 
		}

		public Descriptor(String cliPath, String rtPath, String lsIPAddress,
				String lsPort, String dbName, String dbType,
				String dbUsername, String dbPassword,
				String dbURI, String dbIPAddress, String dbPort) {
			super();
			this.cliPath = cliPath;
			this.rtPath = rtPath;
			this.lsIPAddress = lsIPAddress;
			this.lsPort = lsPort;
			this.dbName = dbName;
			this.dbType = dbType;
			this.dbUsername = dbUsername;
			this.dbPassword = dbPassword;
			this.dbURI = dbURI;
			this.dbIPAddress = dbIPAddress;
			this.dbPort = dbPort;
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) 
				throws FormException {

			cliPath = formData.getString("cliPath");
			rtPath = formData.getString("rtPath"); 
			lsIPAddress = formData.getString("lsIPAddress"); 
			lsPort = formData.getString("lsPort"); 
			dbName = formData.getString("dbName"); 
			dbType = formData.getString("dbType"); 
			dbURI = formData.getString("dbURI"); 
			dbIPAddress = formData.getString("dbIPAddress"); 
			dbPort = formData.getString("dbPort"); 
			dbUsername = formData.getString("dbUsername"); 
			dbPassword = formData.getString("dbPassword"); 

			setDbType(formData.getString("dbType")); 
			save();
			return false;
		}

		public ListBoxModel doFillDbTypeItems(){
			return new ListBoxModel(
					new Option("MySQL", "MySQL", dbType.equals("MySQL")),
					new Option("PostgreSQL", "PostgreSQL", 
							dbType.equals("PostgreSQL"))); 

		}

		/**
		 * If the URI is used, must extract the database type to initialize 
		 * the correct JDBC class driver. 
		 * @return database type 
		 */
		private static String parseType(String uri) { 
			if (uri.toLowerCase().contains("mysql")) { 
				return "mysql"; 
			} else { 
				return "postgresql"; 
			}
		}

		public FormValidation doTestConnection(
				@QueryParameter final String dbName, 
				@QueryParameter final String dbType, 
				@QueryParameter final String dbURI, 
				@QueryParameter final String dbIPAddress, 
				@QueryParameter final String dbPort, 
				@QueryParameter final String dbUsername,
				@QueryParameter final String dbPassword) 
						throws IOException, ServletException {

			Connection connection = null;
			String dburl = null; 

			if (dbURI.isEmpty()) { 
				if (dbName.isEmpty() || dbType.isEmpty() 
						|| dbIPAddress.isEmpty() ||dbPort.isEmpty() 
						|| dbUsername.isEmpty() || dbPassword.isEmpty()) { 
					return FormValidation.error("Missing required field"); 
				}

				//build the connection URI 
				dburl = "jdbc:" + dbType.toLowerCase() + "://" 
						+ dbIPAddress + ":" + dbPort + "/" + dbName; 
			} else {
				dburl = dbURI; 
			}

			//required for both URI or individual fields 
			if (dbUsername.isEmpty() || dbPassword.isEmpty()) { 
				return FormValidation.error("Please specify username "
						+ "and password"); 
			}

			try { 
				
				//initialize class driver 
				if (dbType.equalsIgnoreCase("MySQL") 
						|| parseType(dbType).equalsIgnoreCase("mysql")) {
					Class.forName("com.mysql.jdbc.Driver");
				}

				if (dbType.equalsIgnoreCase("PostgreSQL") 
						|| parseType(dbType).equalsIgnoreCase("postgresql")) { 
					Class.forName("org.postgresql.Driver");
				}

				connection = DriverManager.getConnection(dburl, dbUsername,
						dbPassword);

				return connection != null ? FormValidation.ok("Success") : 
					FormValidation.error("Please check database credentials"); 

			} catch (Exception e) {
				return FormValidation.error("Please check database "
						+ "credentials"); 
			} finally {
				if (connection != null) {
					try {
						connection.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}

		public FormValidation doTestExecutablePath(
				@QueryParameter final String cliPath, 
				@QueryParameter final String rtPath) { 

			//paths must end at executables, OK if paths are empty 
			if ((!cliPath.isEmpty() 
					&& (cliPath.indexOf("itestcli") == -1))){ 
				return FormValidation.error("CLI path does not "
						+ "end at executable"); 
			}

			if((!rtPath.isEmpty() && (rtPath.indexOf("itestrt") == -1))){ 
				return FormValidation.error("RT path does not end"
						+ " at executable"); 
			}

			return FormValidation.ok("Success"); 
		}

		public FormValidation doTestLicenseServerConnection(
				@QueryParameter final String lsIPAddress,
				@QueryParameter final String lsPort) 
						throws IOException, ServletException {
			try {
				//must specify license server 
				if (lsIPAddress.isEmpty()) { 
					return FormValidation.error("Must specify license server"); 
				}

				//test connection 
				try {
					Socket socket = new Socket();
					Integer portNumber = lsPort.isEmpty() ? 
							27000 : Integer.parseInt(lsPort); 

					//InetSocketAddress resolves host name to IP if necessary
					socket.connect(new InetSocketAddress(lsIPAddress, 
							portNumber), 1000);
					
					//if it cannot connect, exception is thrown 
					socket.close();
					return FormValidation.ok("Connected to license server"); 
				} catch (IOException ex) {
					return FormValidation.error("Cannot reach license server"); 
				} 
			} catch (Exception e) {
				e.printStackTrace();
				return FormValidation.error("Client error");
			}
		}
	}

	@Override
	protected String getContents() {
		// required, but JavaDoc doesn't mention function 
		return null;
	}

	@Override
	protected String getFileExtension() {
		// required, but JavaDoc doesn't mention function 
		return null;
	}

	@Override
	public String[] buildCommandLine(FilePath script) {
		// required, but JavaDoc doesn't mention function 
		return null;
	}
}
