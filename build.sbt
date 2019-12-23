lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-xtext-plugin",
    version := "0.1.0",
    organization := "com.idiomaticsoft",
    scalaVersion := "2.12.10",
    sbtPlugin := true,
    sbtVersion := "1.2.8",
    libraryDependencies += "org.eclipse.emf" % "org.eclipse.emf.common" % "2.16.0",
    libraryDependencies += "org.eclipse.emf" % "org.eclipse.emf.ecore" % "2.19.0",
    libraryDependencies += "org.eclipse.emf" % "org.eclipse.emf.ecore.xmi" % "2.16.0",
    libraryDependencies += "org.eclipse.xtext" % "org.eclipse.xtext.builder.standalone" % "2.19.0",
    libraryDependencies += "org.eclipse.xtext" % "org.eclipse.xtext.ecore" % "2.19.0",
    libraryDependencies += "org.eclipse.platform" % "org.eclipse.equinox.app" % "1.3.600"
  )
