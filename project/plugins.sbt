logLevel := Level.Warn

addDependencyTreePlugin

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.16")

addSbtPlugin("com.typesafe.sbt" % "sbt-gzip" % "1.0.2")

addSbtPlugin("com.beautiful-scala" %% "sbt-scalastyle" % "1.5.1")

addSbtPlugin("com.iheart" % "sbt-play-swagger" % "0.10.6-PLAY2.8")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")

addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.4.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.10.3")
