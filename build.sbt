import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtOnCompile
val mainScala = "2.13.5"
val allScala  = Seq("2.12.12", mainScala)

ThisBuild / publishTo := sonatypePublishToBundle.value
skip in publish := true

enablePlugins(GitVersioning)

lazy val root = project
  .in(file("."))
  .aggregate(rezilience.js, rezilience.jvm)
  .settings(
    publish := {},
    publishLocal := {}
  )

lazy val rezilience = crossProject(JSPlatform, JVMPlatform)
  .in(file("rezilience"))
  .settings(
    name := "rezilience",
    version := "0.6.0", // TODO remove!
    organization := "nl.vroste",
    homepage := Some(url("https://github.com/svroonland/rezilience")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := mainScala,
    crossScalaVersions := allScala,
    parallelExecution in Test := false,
    fork in Test := true,
    fork in run := true,
    publishMavenStyle := true,
    scmInfo := Some(
      ScmInfo(url("https://github.com/svroonland/rezilience/"), "scm:git:git@github.com:svroonland/rezilience.git")
    ),
    developers := List(
      Developer(
        "svroonland",
        "Vroste",
        "info@vroste.nl",
        url("https://github.com/svroonland")
      )
    ),
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(
      "dev.zio"                %%% "zio-streams"             % "1.0.4-2",
      "dev.zio"                %%% "zio-test"                % "1.0.4-2" % "test",
      "dev.zio"                %%% "zio-test-sbt"            % "1.0.4-2" % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.4.2"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val docs = project
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(SiteScaladocPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    name := "rezilience",
    skip in publish := true,
    description := "ZIO-native utilities for making asynchronous systems more resilient to failures",
    siteSubdirName in ScalaUnidoc := "api",
    addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), siteSubdirName in ScalaUnidoc),
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(rezilience.js),
    git.remoteRepo := "git@github.com:svroonland/rezilience.git",
    micrositeUrl := "https://svroonland.github.io",
    micrositeBaseUrl := "/rezilience",
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    micrositeHomepage := "https://svroonland.github.io/rezilience/",
    micrositeDocumentationUrl := "docs",
    micrositeAuthor := "vroste",
    micrositeTwitterCreator := "@vroste",
    micrositeGithubOwner := "svroonland",
    micrositeGithubRepo := "rezilience",
    micrositeGitterChannel := false,
    micrositeDataDirectory := file("docs/src/microsite/data"),
    micrositeFooterText := None
  )
  .dependsOn(rezilience.jvm)