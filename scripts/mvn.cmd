@echo off
rem Maven launcher for this project (E:\develop\mvn.cmd is broken; call classworlds directly)
set MVN_HOME=E:\develop\apache-maven-3.9.4
if "%MAVEN_PROJECTBASEDIR%"=="" set MAVEN_PROJECTBASEDIR=%CD%
java -cp "%MVN_HOME%\boot\plexus-classworlds-2.7.0.jar" "-Dclassworlds.conf=%MVN_HOME%\bin\m2.conf" "-Dmaven.home=%MVN_HOME%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.codehaus.plexus.classworlds.launcher.Launcher %*
