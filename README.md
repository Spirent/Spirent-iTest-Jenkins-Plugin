itest-plugin
============

The iTest Plugin for Jenkins CI

============

Run the following command within this directory to generate Eclipse .project files

mvn -DdownloadSources=true -DdownloadJavadocs=true 
-DoutputDirectory=target/eclipse-classes eclipse:eclipse

============

Run deploy.bat to build and deploy the plugin to Jenkins. 