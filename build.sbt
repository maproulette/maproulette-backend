import java.io.{BufferedWriter, FileWriter}

import scala.io.Source

name := "MapRouletteAPI"

version := "4.0.0"

scalaVersion := "2.13.10"

Universal / packageName := "MapRouletteAPI"

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

scalacOptions ++= Seq(
  // Show warning feature details in the console
  "-feature",
  // Enable routes file splitting
  "-language:reflectiveCalls",
  "-Wunused:imports"
)

javaOptions in Test ++= Option(System.getProperty("config.file")).map("-Dconfig.file=" + _).toSeq

javaOptions in Compile ++= Seq(
  "-Xmx2G",
  // Increase stack size for compilation
  "-Xss32M"
)

lazy val generateRoutesFile = taskKey[Unit]("Builds the API V2 Routes File")
generateRoutesFile := {
  val generatedFile = baseDirectory.value / "conf/generated.routes"
  if (!generatedFile.exists()) {
    generatedFile.createNewFile()

    // The apiv2.routes file should always be last as it contains the catch all routes
    val routeFiles = Seq(
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
      "v2.api"
    )
    println(s"Generating Routes File from ${routeFiles.mkString(",")}")
    val writer = new BufferedWriter(new FileWriter(generatedFile))
    routeFiles.foreach(file => {
      val currentFile = Source.fromFile(baseDirectory.value / "conf/v2_route" / file).getLines()
      for (line <- currentFile) {
        writer.write(s"$line\n")
      }
    })
    writer.close()
  }
}

(compile in Compile) := ((compile in Compile) dependsOn generateRoutesFile).value

lazy val deleteRoutesFile = taskKey[Unit]("Deletes the generated Routes File")
deleteRoutesFile := {
  val generatedFile = baseDirectory.value / "conf/generated.routes"
  if (generatedFile.exists()) {
    println(s"Deleting the Routes file ${generatedFile.getAbsolutePath}")
    generatedFile.delete()
  }
}

cleanFiles := (baseDirectory.value / "conf" / "generated.routes") +: cleanFiles.value

lazy val regenerateRoutesFile = taskKey[Unit]("Regenerates the routes file")
regenerateRoutesFile := Def.sequential(deleteRoutesFile, generateRoutesFile).value
