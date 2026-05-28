logLevel := Level.Warn

addDependencyTreePlugin

// https://github.com/playframework/playframework/releases
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.9.11")

addSbtPlugin("com.github.sbt" % "sbt-gzip" % "2.0.0")

addSbtPlugin("com.beautiful-scala" %% "sbt-scalastyle" % "1.5.1")

addSbtPlugin("io.github.play-swagger" % "sbt-play-swagger" % "1.7.3")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.5.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.6")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")

// https://github.com/sbt/sbt/issues/6997
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
