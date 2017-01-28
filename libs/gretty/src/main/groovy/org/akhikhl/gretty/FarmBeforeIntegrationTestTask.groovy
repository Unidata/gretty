/*
 * Gretty
 *
 * Copyright (C) 2013-2015 Andrey Hihlovskiy and contributors.
 *
 * See the file "LICENSE" for copying and usage permission.
 * See the file "CONTRIBUTORS" for complete list of contributors.
 */
package org.akhikhl.gretty
import org.gradle.api.Project
import org.gradle.process.JavaForkOptions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
/**
 *
 * @author akhikhl
 */
class FarmBeforeIntegrationTestTask extends FarmStartTask {

  protected static final Logger log = LoggerFactory.getLogger(FarmBeforeIntegrationTestTask)

  private String integrationTestTask_
  private boolean integrationTestTaskAssigned

  FarmBeforeIntegrationTestTask() {
    def thisTask = this
    mustRunAfter {
      List projects = project.rootProject.allprojects as List
      def result = []
      int thisProjectIndex = projects.indexOf(project)
      if(thisProjectIndex > 0)
        result.addAll projects[0..thisProjectIndex - 1].findAll { it.extensions.findByName('farms') }.collectMany { proj ->
          proj.extensions.farms.farmsMap.keySet().collect { proj.tasks['farmAfterIntegrationTest' + it] }
        }
      def farms = project.extensions.farms.farmsMap.keySet() as List
      def thisFarmIndex = farms.indexOf(farmName)
      if(thisFarmIndex > 0)
        result.addAll farms[0..thisFarmIndex - 1].collect { project.tasks['farmAfterIntegrationTest' + it] }
      result = result.findAll { otherTask ->
        thisTask.integrationTestTask != otherTask.integrationTestTask || !thisTask.getWebAppProjects().intersect(otherTask.getWebAppProjects())
      }
      result
    }
    doFirst {
      getWebAppProjects().each { proj ->
        proj.tasks.each { t ->
          if(GradleUtils.instanceOf(t, 'org.akhikhl.gretty.AppBeforeIntegrationTestTask') ||
             GradleUtils.instanceOf(t, 'org.akhikhl.gretty.AppAfterIntegrationTestTask'))
            if(t.enabled)
              t.enabled = false
        }
      }
      project.subprojects.each { proj ->
        proj.tasks.each { t ->
          if(GradleUtils.instanceOf(t, 'org.akhikhl.gretty.AppBeforeIntegrationTestTask') ||
             GradleUtils.instanceOf(t, 'org.akhikhl.gretty.AppAfterIntegrationTestTask') ||
             GradleUtils.instanceOf(t, 'org.akhikhl.gretty.FarmBeforeIntegrationTestTask') ||
             GradleUtils.instanceOf(t, 'org.akhikhl.gretty.FarmAfterIntegrationTestTask') ||
             GradleUtils.instanceOf(t, 'org.akhikhl.gretty.FarmIntegrationTestTask'))
            if(t.enabled)
              t.enabled = false
        }
      }
    }
  }

  @Override
  protected boolean getDefaultJacocoEnabled() {
    true
  }

  @Override
  protected boolean getIntegrationTest() {
    true
  }

  String getIntegrationTestTask() {
    integrationTestTask_ ?: new FarmConfigurer(project).getProjectFarm(farmName).integrationTestTask
  }

  boolean getIntegrationTestTaskAssigned() {
    integrationTestTaskAssigned
  }

  @Override
  protected boolean getManagedClassReload(ServerConfig sconfig) {
    // disable managed class reloads on integration tests
    false
  }

  void integrationTestTask(String integrationTestTask) {
    if(integrationTestTaskAssigned) {
      log.warn '{}.integrationTestTask is already set to "{}", so "{}" is ignored', name, getIntegrationTestTask(), integrationTestTask
      return
    }
    integrationTestTask_ = integrationTestTask
    def thisTask = this
    project.rootProject.allprojects.each { proj ->
      proj.tasks.all { t ->
        if(getWebAppProjects().contains(t.project)) {
          if (t.name == thisTask.integrationTestTask) {
            t.mustRunAfter thisTask
            thisTask.mustRunAfter proj.tasks.testClasses
            if (t.name != 'test' && project.tasks.findByName('test'))
              thisTask.mustRunAfter project.tasks.test
            if (GradleUtils.instanceOf(t, 'org.gradle.process.JavaForkOptions'))
              t.doFirst {
                if (thisTask.didWork)
                  passSystemPropertiesToIntegrationTask(proj, t)
              }
              t.doLast {
                if (thisTask.didWork)
                  // See note in AppBeforeIntegrationTest.integrationTestTask().
                  t.systemProperties.keySet().removeAll { it.startsWith("gretty.") }
              }
          } else if (GradleUtils.instanceOf(t, 'org.akhikhl.gretty.AppBeforeIntegrationTestTask') && t.integrationTestTask == thisTask.integrationTestTask)
            t.mustRunAfter thisTask // need this to be able to disable AppBeforeIntegrationTestTask in doFirst
        }
      }
    }
    integrationTestTaskAssigned = true
  }

  protected void passSystemPropertiesToIntegrationTask(Project webappProject, JavaForkOptions task) {

    def host = serverStartInfo.host

    task.systemProperty 'gretty.host', host

    FarmConfigurer configurer = new FarmConfigurer(project)
    FarmExtension tempFarm = new FarmExtension(project)
    configurer.configureFarm(tempFarm, new FarmExtension(project, serverConfig: serverConfig, webAppRefs: webAppRefs), configurer.getProjectFarm(farmName))
    def options = tempFarm.webAppRefs.find { key, value -> configurer.resolveWebAppRefToProject(key) == webappProject }.value
    def webappConfig = configurer.getWebAppConfigForProject(options, webappProject, inplace, inplaceMode)
    ProjectUtils.prepareToRun(project, webappConfig)

    def contextPath
    if(task.hasProperty('contextPath') && task.contextPath != null) {
      contextPath = task.contextPath
      if(!contextPath.startsWith('/'))
        contextPath = '/' + contextPath
    }
    else
      contextPath = webappConfig.contextPath

    task.systemProperty 'gretty.contextPath', contextPath

    String preferredProtocol
    String preferredBaseURI

    def httpPort = serverStartInfo.httpPort
    String httpBaseURI
    if(httpPort) {
      task.systemProperty 'gretty.port', httpPort
      task.systemProperty 'gretty.httpPort', httpPort
      httpBaseURI = "http://${host}:${httpPort}${contextPath}"
      task.systemProperty 'gretty.baseURI', httpBaseURI
      task.systemProperty 'gretty.httpBaseURI', httpBaseURI
      preferredProtocol = 'http'
      preferredBaseURI = httpBaseURI
    }

    def httpsPort = serverStartInfo.httpsPort
    String httpsBaseURI
    if(httpsPort) {
      task.systemProperty 'gretty.httpsPort', httpsPort
      httpsBaseURI = "https://${host}:${httpsPort}${contextPath}"
      task.systemProperty 'gretty.httpsBaseURI', httpsBaseURI
      preferredProtocol = 'https'
      preferredBaseURI = httpsBaseURI
    }

    if(preferredProtocol)
      task.systemProperty 'gretty.preferredProtocol', preferredProtocol
    if(preferredBaseURI)
      task.systemProperty 'gretty.preferredBaseURI', preferredBaseURI

    task.systemProperty 'gretty.farm', (farmName ?: 'default')
  }
}
