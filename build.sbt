import java.io.{BufferedWriter, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDate
import java.time.ZoneOffset
import sbtbuildinfo.ScalaCaseClassRenderer
import scala.io.Source
import scala.util.Using

name := "MapRouletteAPI"

version := "4.0.0"

scalaVersion := "2.13.10"

Universal / packageName := "MapRouletteAPI"

// Developers can run 'sbt format' to easily format their source; this is required to pass a PR build.
addCommandAlias("format", "scalafmtAll; scalafmtSbt; scalafixAll")

// Setup BuildInfo plugin to write important build-time values to a generated file (org.maproulette.models.service.info.BuildInfo)
enablePlugins(BuildInfoPlugin)
buildInfoPackage := "org.maproulette.models.service.info"
buildInfoRenderFactory := ScalaCaseClassRenderer.apply
buildInfoOptions ++= Seq(
  BuildInfoOption.ImportScalaPredef,
  BuildInfoOption.ToJson,
  BuildInfoOption.ToMap
)
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoKeys += BuildInfoKey.action("buildDate")(LocalDate.now(ZoneOffset.UTC).toString)
buildInfoKeys += BuildInfoKey.action("javaVersion")(sys.props("java.version"))
buildInfoKeys += BuildInfoKey.action("javaVendor")(sys.props("java.vendor"))

// Configure scalastyle. This does not run during compile, run it with 'sbt scalastyle' or 'sbt test:scalastyle'.
Compile / scalastyleConfig := baseDirectory.value / "conf/scalastyle-config.xml"
Test / scalastyleConfig := baseDirectory.value / "conf/scalastyle-config.xml"

// Setup the scalafix plugin
inThisBuild(
  List(
    semanticdbEnabled := true,
    semanticdbOptions += "-P:semanticdb:synthetics:on",
    semanticdbVersion := scalafixSemanticdb.revision,
    scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)
  )
)

lazy val `MapRouletteV2` = (project in file(".")).enablePlugins(PlayScala, SbtWeb, SwaggerPlugin)

swaggerDomainNameSpaces := Seq(
  "org.maproulette.framework.model",
  "org.maproulette.models",
  "org.maproulette.exception",
  "org.maproulette.session",
  "org.maproulette.actions",
  "org.maproulette.data"
)

swaggerRoutesFile := "generated.routes"

pipelineStages := Seq(gzip)

routesGenerator := InjectedRoutesGenerator

libraryDependencies ++= Seq(
  jdbc,
  jdbc % Test,
  ehcache,
  ws,
  evolutions,
  specs2 % Test,
  filters,
  guice,
  // NOTE: Be careful upgrading sangria and play-json as binary incompatibilities can break graphql and the entire UI.
  //       See the compatibility matrix here https://github.com/sangria-graphql/sangria-play-json
  "org.sangria-graphql"    %% "sangria-play-json"  % "2.0.1",
  "org.sangria-graphql"    %% "sangria"            % "2.0.1",
  "com.typesafe.play"      %% "play-json-joda"     % "2.8.2",
  "com.typesafe.play"      %% "play-json"          % "2.8.2",
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test,
  "org.scalatestplus"      %% "mockito-4-5"        % "3.2.12.0" % Test,
  // NOTE: The swagger-ui package is used to obtain the static distribution of swagger-ui, the files included at runtime
  // and are served by the webserver at route '/assets/lib/swagger-ui/'. We have a few customized swagger files in dir
  // 'public/swagger'.
  "org.webjars"             % "swagger-ui"      % "4.14.3",
  "org.playframework.anorm" %% "anorm"          % "2.6.10",
  "org.playframework.anorm" %% "anorm-postgres" % "2.6.10",
  "org.postgresql"          % "postgresql"      % "42.5.0",
  "net.postgis"             % "postgis-jdbc"    % "2021.1.0",
  "joda-time"               % "joda-time"       % "2.12.0",
  // TODO(ljdelight): The vividsolutions package was moved to the Eclipse Foundation as LocationTech.
  //                  See the upgrade guide https://github.com/locationtech/jts/blob/master/MIGRATION.md
  "com.vividsolutions" % "jts"                 % "1.13",
  "org.wololo"         % "jts2geojson"         % "0.14.3",
  "org.apache.commons" % "commons-lang3"       % "3.12.0",
  "commons-codec"      % "commons-codec"       % "1.14",
  "com.typesafe.play"  %% "play-mailer"        % "8.0.1",
  "com.typesafe.play"  %% "play-mailer-guice"  % "8.0.1",
  "com.typesafe.akka"  %% "akka-cluster-tools" % "2.6.20",
  "com.typesafe.akka"  %% "akka-cluster-typed" % "2.6.20",
  "com.typesafe.akka"  %% "akka-slf4j"         % "2.6.20",
  "net.debasishg"      %% "redisclient"        % "3.42",
  "com.github.blemale" %% "scaffeine"          % "5.2.1"
)

val jacksonVersion         = "2.13.4"
val jacksonDatabindVersion = "2.13.4.2"

val jacksonOverrides = Seq(
  "com.fasterxml.jackson.core"     % "jackson-core",
  "com.fasterxml.jackson.core"     % "jackson-annotations",
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8",
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310"
).map(_ % jacksonVersion)

val jacksonDatabindOverrides = Seq(
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion
)

val akkaSerializationJacksonOverrides = Seq(
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor",
  "com.fasterxml.jackson.module"     % "jackson-module-parameter-names",
  "com.fasterxml.jackson.module"     %% "jackson-module-scala"
).map(_ % jacksonVersion)

libraryDependencies ++= jacksonDatabindOverrides ++ jacksonOverrides ++ akkaSerializationJacksonOverrides

resolvers ++= Resolver.sonatypeOssRepos("releases")

// Some suggested scalac compiler options. These will print but nothing will fail the build.
// https://gist.githubusercontent.com/tabdulradi/aa7450921756cd22db6d278100b2dac8/raw/ad80d738758f81b576bac4fe6188625646cf4ddf/scalac-compiler-flags-2.13.sbt
//
scalacOptions ++= Seq(
  "-deprecation", // Emit warning and location for usages of deprecated APIs.
  "-encoding",
  "utf-8",                  // Specify character encoding used by source files.
  "-explaintypes",          // Explain type errors in more detail.
  "-feature",               // Emit warning and location for usages of features that should be imported explicitly.
  "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
  //  "-language:experimental.macros",   // Allow macro definition (besides implementation and application). Disabled, as this will significantly change in Scala 3
  "-language:higherKinds", // Allow higher-kinded types
  //  "-language:implicitConversions",   // Allow definition of implicit functions called views. Disabled, as it might be dropped in Scala 3. Instead use extension methods (implemented as implicit class Wrapper(val inner: Foo) extends AnyVal {}
  "-unchecked",  // Enable additional warnings where generated code depends on assumptions.
  "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
  //  "-Xfatal-warnings",                  // Fail the compilation if there are any warnings. Disable, there are many issues to resolve :-)
  "-Xlint:adapted-args",           // Warn if an argument list is modified to match the receiver.
  "-Xlint:constant",               // Evaluation of a constant arithmetic expression results in an error.
  "-Xlint:delayedinit-select",     // Selecting member of DelayedInit.
  "-Xlint:doc-detached",           // A Scaladoc comment appears to be detached from its element.
  "-Xlint:inaccessible",           // Warn about inaccessible types in method signatures.
  "-Xlint:infer-any",              // Warn when a type argument is inferred to be `Any`.
  "-Xlint:missing-interpolator",   // A string literal appears to be missing an interpolator id.
  "-Xlint:nullary-unit",           // Warn when nullary methods return Unit.
  "-Xlint:option-implicit",        // Option.apply used implicit view.
  "-Xlint:package-object-classes", // Class or object defined in package object.
  "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
  "-Xlint:private-shadow",         // A private field (or class parameter) shadows a superclass field.
  "-Xlint:stars-align",            // Pattern sequence wildcard must align with sequence component.
  "-Xlint:type-parameter-shadow",  // A local type parameter shadows a type already in scope.
//  "-Xlint:unused",                 // TODO check if we still need -Wunused below
  "-Xlint:nonlocal-return",    // A return statement used an exception for flow control.
  "-Xlint:implicit-not-found", // Check @implicitNotFound and @implicitAmbiguous messages.
  "-Xlint:implicit-recursion",
  "-Xlint:serial",      // @SerialVersionUID on traits and non-serializable classes.
  "-Xlint:valpattern",  // Enable pattern checks in val definitions.
  "-Xlint:eta-zero",    // Warn on eta-expansion (rather than auto-application) of zero-ary method.
  "-Xlint:eta-sam",     // Warn on eta-expansion to meet a Java-defined functional interface that is not explicitly annotated with @FunctionalInterface.
  "-Xlint:deprecation", // Enable linted deprecations.
  "-Wdead-code",        // Warn when dead code is identified.
//  "-Wextra-implicit",   // Warn when more than one implicit parameter section is defined.
//  "-Wmacros:both",      // Lints code before and after applying a macro
//  "-Wnumeric-widen",    // Warn when numerics are widened.
//  "-Woctal-literal",    // Warn on obsolete octal syntax.
  "-Wunused:imports", // Warn if an import selector is not referenced.
//  "-Wunused:patvars",   // Warn if a variable bound in a pattern is unused.
//  "-Wunused:privates",  // Warn if a private member is unused.
//  "-Wunused:locals",    // Warn if a local definition is unused.
//  "-Wunused:explicits", // Warn if an explicit parameter is unused.
//  "-Wunused:implicits", // Warn if an implicit parameter is unused.
//  "-Wunused:params",    // Enable -Wunused:explicits,implicits.
//  "-Wunused:linted",
//  "-Wvalue-discard", // Warn when non-Unit expression results are unused.
  "-Ybackend-parallelism",
  "8",                                         // Enable paralellisation — change to desired number!
  "-Ycache-plugin-class-loader:last-modified", // Enables caching of classloaders for compiler plugins
  "-Ycache-macro-class-loader:last-modified"   // and macro definitions. This can lead to performance improvements.
)

Test / javaOptions ++= Option(System.getProperty("config.file")).map("-Dconfig.file=" + _).toSeq

// Disable building docs to speed up the build
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false

Compile / javaOptions ++= Seq(
  "-Xmx2G",
  // Increase stack size for compilation
  "-Xss32M"
)

// The apiv2.routes file should always be last as it contains the catch-all routes
val routeFiles: Seq[String] = Seq(
  "challenge.api",
  "changes.api",
  "comment.api",
  "data.api",
  "keyword.api",
  "notification.api",
  "project.api",
  "review.api",
  "snapshot.api",
  "task.api",
  "user.api",
  "virtualchallenge.api",
  "virtualproject.api",
  "bundle.api",
  "team.api",
  "follow.api",
  "leaderboard.api",
  "service.api",
  "v2.api"
)

lazy val generateRoutesFile = taskKey[Unit]("Build the API V2 Routes File")
generateRoutesFile := {
  val s: TaskStreams          = streams.value
  val genRoutesFilePath: File = file(s"${baseDirectory.value}/conf/${swaggerRoutesFile.value}")

  def getLastModifiedTime(file: File): Long = {
    Files.readAttributes(file.toPath, classOf[BasicFileAttributes]).lastModifiedTime().toMillis
  }

  def generateRoutes(): Unit = {
    genRoutesFilePath.createNewFile()
    s.log.info(
      s"Generating swagger routes file ${genRoutesFilePath} from ${routeFiles.mkString(",")}"
    )

    Using(new BufferedWriter(new FileWriter(genRoutesFilePath, StandardCharsets.UTF_8))) { writer =>
      routeFiles.foreach(file => {
        val sourceFile = baseDirectory.value / "conf/v2_route" / file
        s.log.info(s"Including contents of ${file} in swagger routes file ${genRoutesFilePath}")

        Using(Source.fromFile(sourceFile)(StandardCharsets.UTF_8)) { f =>
          for (line <- f.getLines()) {
            writer.write(s"$line\n")
          }
        }
      })
    }

    s.log.success(s"Successfully created swagger routes file ${genRoutesFilePath}")
  }

  if (!genRoutesFilePath.exists()) {
    generateRoutes()
  } else {
    val genRoutesLastModified = getLastModifiedTime(genRoutesFilePath)
    val dependentFilesLastModified =
      routeFiles.map(file => getLastModifiedTime(baseDirectory.value / "conf/v2_route" / file))
    val anyFileChanged = dependentFilesLastModified.exists(_ > genRoutesLastModified)

    if (anyFileChanged) {
      genRoutesFilePath.delete()
      generateRoutes()
    } else {
      s.log.info(s"Swagger routes file ${genRoutesFilePath} is up to date.")
    }
  }
}

Compile / compile := (Compile / compile).dependsOn(generateRoutesFile).value
cleanFiles := (file(s"${baseDirectory.value}/conf/${swaggerRoutesFile.value}")) +: cleanFiles.value

lazy val deleteRoutesFile = taskKey[Unit]("Delete the generated swagger routes file")
deleteRoutesFile := {
  val s: TaskStreams          = streams.value
  val genRoutesFilePath: File = file(s"${baseDirectory.value}/conf/${swaggerRoutesFile.value}")
  s.log.info(s"Deleting the swagger routes file ${genRoutesFilePath.getAbsolutePath}")
  genRoutesFilePath.delete()
}

lazy val regenerateRoutesFile = taskKey[Unit]("Regenerate the swagger routes file")
regenerateRoutesFile := Def.sequential(deleteRoutesFile, generateRoutesFile).value

swaggerV3 := true
