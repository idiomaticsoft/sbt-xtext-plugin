/*******************************************************************************
 * Copyright (c) 2019 Idiomaticsoft S.R.L. and others.
 * This program and the accompanying materials are made
 * available under the terms of the APACHE LICENSE, VERSION 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Contributors:
 *  Edmundo Lopez B. (Idiomaticsoft S.R.L.) - initial implementation
 *******************************************************************************/


package sbtxtext

import sbt._
import Keys._
import plugins._
import java.io.File
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.plugin.EcorePlugin
import org.eclipse.xtext.builder.standalone.ILanguageConfiguration
import org.eclipse.xtext.builder.standalone.LanguageAccessFactory
import org.eclipse.xtext.builder.standalone.StandaloneBuilderModule
import com.google.inject.Module
import com.google.inject.Guice
import org.eclipse.xtext.builder.standalone.StandaloneBuilder
import com.google.common.collect.Sets.newLinkedHashSet;
import org.eclipse.xtext.generator.OutputConfiguration
import collection.JavaConverters._
import org.eclipse.emf.ecore.resource.ResourceSet
import org.eclipse.xtext.builder.standalone.LanguageAccess
import org.eclipse.xtext.mwe.NameBasedFilter
import org.eclipse.xtext.mwe.PathTraverser
import sbt.internal.util.ManagedLogger
import java.util.zip.ZipException
import java.io.IOException
import java.util.jar.JarFile
import org.eclipse.xtext.resource.XtextResourceSet
import org.eclipse.xtext.resource.impl.ResourceDescriptionsData
import java.{util => ju}
import org.eclipse.emf.ecore.resource.Resource
import org.eclipse.xtext.util.CancelIndicator
import org.eclipse.xtext.EcoreUtil2
import org.eclipse.xtext.validation.CheckMode
import org.eclipse.xtext.builder.standalone.IIssueHandler
import org.eclipse.xtext.generator.GeneratorContext
import org.eclipse.xtext.resource.persistence.StorageAwareResource
import org.eclipse.xtext.generator.JavaIoFileSystemAccess
import org.eclipse.xtext.util.UriUtil
import org.eclipse.xtext.generator.JavaIoFileSystemAccess.IFileCallback

class Language(
    setup: String,
    outputDirectory: Option[File] = None
) extends ILanguageConfiguration {
  def getOutputConfigurations() = {
    val outputConf = new OutputConfiguration("defaultOutputConfiguration")
    Set(outputConf).asJava
  }
  def getSetup() = setup
  def isJavaSupport() = false
}

class SbtStandaloneBuilderModule extends StandaloneBuilderModule with Module

object SbtXtextPlugin extends AutoPlugin {

  val configuredFsas =
    collection.mutable.HashMap[LanguageAccess, JavaIoFileSystemAccess]()

  object autoImport {
    lazy val sbtXtextLanguageConfigurations =
      settingKey[Seq[Language]]("The language configuration.")
    lazy val sbtXtextGenDir =
      settingKey[File]("The file where the code is going to be generated.")
  }

  import autoImport._

  override def requires = JvmPlugin

  val filter = ScopeFilter(inAnyProject, inAnyConfiguration)

  override lazy val projectSettings = Seq(
    sbtXtextGenDir := ((Compile / target).value / "xtext-gen"),
    sourceGenerators in Compile += Def.task {

      val logger = streams.value.log
      // autoAddToPlatformResourceMap
      addToPlatformResourceMap(baseDirectory.value, logger)

      val languages = Map.empty ++ (new LanguageAccessFactory())
        .createLanguageAccess(
          sbtXtextLanguageConfigurations.value.toList.asJava,
          this.getClass().getClassLoader()
        )
        .asScala
      logger.info("Collecting source models.")
      val startedAt = System.currentTimeMillis
      val classPathEntries = (Compile / dependencyClasspathAsJars).value
      val injector = Guice.createInjector(new SbtStandaloneBuilderModule());
      val resourceSet = injector.getInstance(classOf[XtextResourceSet])
      val issueHandler = injector.getInstance(classOf[IIssueHandler])
      var rootsToTravers = classPathEntries.map(_.data.getAbsolutePath())
      val sourceDirs =
        (Compile / unmanagedSourceDirectories).value.map(_.getAbsolutePath())
      val sourceResourceURIs = collectResources(
        languages,
        sourceDirs,
        resourceSet,
        logger
      )
      val allResourcesURIs = sourceResourceURIs ++ collectResources(
        languages,
        rootsToTravers,
        resourceSet,
        logger
      )
      val baseDir = sbtXtextGenDir.value
      val allClassPathEntries = (sourceDirs ++ rootsToTravers)
      val index = new ResourceDescriptionsData(new ju.ArrayList())
      allResourcesURIs.foreach(uri => {
        val resource = resourceSet.getResource(uri, true)
        fillIndex(languages, uri, resource, index)
      })
      installIndex(resourceSet, index)
      logger.info("Validate and generate.")
      val (didValidate, validatedResources) = sourceResourceURIs
        .map((uri) => {
          val resource = resourceSet.getResource(uri, true)
          resource.getContents // full initialize
          EcoreUtil2.resolveLazyCrossReferences(
            resource,
            CancelIndicator.NullImpl
          )
          resource
        })
        .foldLeft((true, List[Resource]()))(
          (b, r) =>
            (b._1 && validate(r, languages, issueHandler, logger), r :: b._2)
        )
      val generatedFiles = collection.mutable.Set[File]()
      if (didValidate) {
        generate(
          languages,
          validatedResources,
          baseDir,
          sourceDirs,
          generatedFiles,
          logger
        )
      }
      generatedFiles.toSeq
    }.taskValue
  )

  def getFileSystemAccess(
      language: LanguageAccess,
      baseDir: File
  ): JavaIoFileSystemAccess = {
    var someFsa = configuredFsas.get(language)
    someFsa.getOrElse {
      val fsa = language.createFileSystemAccess(baseDir)
      configuredFsas.put(language, fsa)
      fsa
    }
  }

  def generate(
      languages: Map[String, LanguageAccess],
      sourceResources: List[Resource],
      baseDir: File,
      sourceDirs: Seq[String],
      generatedFiles: collection.mutable.Set[File],
      logger: ManagedLogger
  ) = {
    val context = new GeneratorContext
    context.setCancelIndicator(CancelIndicator.NullImpl)
    sourceResources.foreach(resource => {
      logger.info(
        "Starting generator for input: '" + resource
          .getURI()
          .lastSegment() + "'"
      );
      registerCurrentSource(languages, resource.getURI(), baseDir, sourceDirs)
      val access = languages(resource.getURI().fileExtension())
      val fileSystemAccess = getFileSystemAccess(access, baseDir)
      resource match {
        case resourceStorageFacade: StorageAwareResource => {
          Option(resourceStorageFacade.getResourceStorageFacade())
            .map(_.saveResource(resourceStorageFacade, fileSystemAccess))
        }
        case _: Resource => // do nothing
      }
      fileSystemAccess.setCallBack(new IFileCallback {
        def fileAdded(file: File): Unit = {
			if (file.ext == "scala") {
 				generatedFiles.add(file)
			}
        }
        def fileDeleted(file: File): Unit = generatedFiles.remove(file)
      })
      access.getGenerator.generate(resource, fileSystemAccess, context)
    })

  }
  def registerCurrentSource(
      languages: Map[String, LanguageAccess],
      uri: URI,
      baseDir: File,
      sourceDirs: Seq[String]
  ) {
    val fsa = getFileSystemAccess(languages(uri.fileExtension()), baseDir)
    val absoluteSource = sourceDirs
      .map(x => UriUtil.createFolderURI(new File(x)))
      .find(UriUtil.isPrefixOf(_, uri))
    if (Option(absoluteSource).isEmpty) {
      throw new IllegalStateException(
        s"Resource ${uri} is not contained in any of the known source folders ${sourceDirs}."
      )
    }
    val projectBaseURI = UriUtil.createFolderURI(baseDir)

  }

  def validate(
      resource: Resource,
      languageAccess: Map[String, LanguageAccess],
      issueHandler: IIssueHandler,
      logger: ManagedLogger
  ) = {
    logger.info(
      "Starting validation for input: '" + resource.getURI().lastSegment() + "'"
    );
    val resourceValidator =
      languageAccess(resource.getURI().fileExtension()).getResourceValidator();
    val validationResult =
      resourceValidator.validate(resource, CheckMode.ALL, null);
    issueHandler.handleIssue(validationResult)
  }

  def installIndex(
      resourceSet: XtextResourceSet,
      index: ResourceDescriptionsData
  ) {
    ResourceDescriptionsData.ResourceSetAdapter
      .installResourceDescriptionsData(resourceSet, index)
  }

  private def createTempDir(tmpClassDirectory: String) = {
    val tmpDir = new File(tmpClassDirectory)
    if (!tmpDir.mkdirs() && !tmpDir.exists()) {
      throw new IllegalArgumentException(
        "Couldn't create directory '" + tmpClassDirectory + "'."
      )
    }
    tmpDir
  }

  def fillIndex(
      languageAccess: Map[String, LanguageAccess],
      uri: URI,
      resource: Resource,
      index: ResourceDescriptionsData
  ) {
    val description =
      languageAccess(uri.fileExtension()).getResourceDescriptionManager
        .getResourceDescription(resource)
    index.addDescription(uri, description)
  }

  private def addToPlatformResourceMap(file: File, logger: ManagedLogger) = {
    logger.info(
      "Adding project '" + file.getName() + "' with path '" + file
        .toURI()
        .toString() + "' to Platform Resource Map"
    )
    val uri = URI.createURI(file.toURI().toString())
    EcorePlugin.getPlatformResourceMap().put(file.getName(), uri)
  }

  private def collectResources(
      languages: Map[String, LanguageAccess],
      roots: Seq[String],
      resourceSet: ResourceSet,
      logger: ManagedLogger
  ) = {
    val extensions = languages.keys.toList.mkString("|")
    val nameBasedFilter = new NameBasedFilter
    nameBasedFilter.setRegularExpression(".*\\.(?:(" + extensions + "))$")
    var resources = List[org.eclipse.emf.common.util.URI]()
    val modelsFound = new PathTraverser().resolvePathes(
      roots.toList.asJava,
      (input) => {
        val matches = nameBasedFilter.matches(input)
        if (matches) {
          logger.info("Adding file '" + input + "'");
          resources = input :: resources
        }
        matches
      }
    )
    modelsFound.asMap.asScala.foreach(e => {
      val (uri, resource) = e
      val file = new File(uri)
      if (Option(resource).isDefined && !(file.isDirectory()) && file.name
            .endsWith(".jar")) {
        registerBundle(file, logger)
      }
    })
    resources
  }

  // translated from from org.eclipse.emf.mwe.utils.StandaloneSetup.registerBundle(File)
  def registerBundle(file: File, logger: ManagedLogger): Unit = {

    var jarFile: JarFile = null
    try {
      jarFile = new JarFile(file);
      val someManifest = jarFile.getManifest()
      Option(someManifest).map { manifest =>
        var name = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
        if (Option(name).isDefined) {
          val indexOf = name.indexOf(';')
          if (indexOf > 0)
            name = name.substring(0, indexOf);
          if (EcorePlugin.getPlatformResourceMap().containsKey(name))
            return
          val path = "archive:" + file.toURI() + "!/";
          val uri = URI.createURI(path);
          EcorePlugin.getPlatformResourceMap().put(name, uri);
        }
      }
    } catch {
      case e: ZipException => {
        logger.error("Could not open Jar file " + file.getAbsolutePath() + ".");
      }
      case e: Exception => {
        logger.error(file.absolutePath)
        logger.error(e.getMessage())
      }
    } finally {
      try {
        if (Option(jarFile).isDefined)
          jarFile.close();
      } catch {
        case e: IOException => {
          logger.error(jarFile.toString())
        }
      }
    }
  }

}
