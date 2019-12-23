ThisBuild / version := "0.1.0"
ThisBuild / organization := "com.idiomaticsoft"
ThisBuild / description := "Xtext plugin for the Scala Build Tool (SBT)."
ThisBuild / licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))
ThisBuild / bintrayOrganization := Some("idiomaticsoft")


lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-xtext-plugin",
    scalaVersion := "2.12.10",
    sbtPlugin := true,
    sbtVersion := "1.2.8",
    publishMavenStyle := false,
    bintrayRepository := "sbt-plugins",
    libraryDependencies += "org.eclipse.emf" % "org.eclipse.emf.common" % "2.16.0",
    libraryDependencies += "org.eclipse.emf" % "org.eclipse.emf.ecore" % "2.19.0",
    libraryDependencies += "org.eclipse.emf" % "org.eclipse.emf.ecore.xmi" % "2.16.0",
    libraryDependencies += "org.eclipse.xtext" % "org.eclipse.xtext.builder.standalone" % "2.19.0",
    libraryDependencies += "org.eclipse.xtext" % "org.eclipse.xtext.ecore" % "2.19.0",
    libraryDependencies += "org.eclipse.platform" % "org.eclipse.equinox.app" % "1.3.600"
  )
