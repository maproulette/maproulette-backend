logLevel := Level.Warn

addDependencyTreePlugin

// https://github.com/playframework/playframework/releases
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.9.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-gzip" % "1.0.2")

addSbtPlugin("com.beautiful-scala" %% "sbt-scalastyle" % "1.5.1")

addSbtPlugin("io.github.play-swagger" % "sbt-play-swagger" % "1.6.2")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")

addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.4.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.3")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

// https://github.com/sbt/sbt/issues/6997
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
