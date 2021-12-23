logLevel := Level.Warn

// The Typesafe repository
resolvers ++= Seq(
  Resolver.bintrayRepo("scalaz", "releases"),
  Resolver.bintrayIvyRepo("iheartradio", "sbt-plugins")
)

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.11")

addSbtPlugin("com.typesafe.sbt" % "sbt-gzip" % "1.0.2")

addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0")

addSbtPlugin("com.iheart" % "sbt-play-swagger" % "0.10.6-PLAY2.8")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.3.1")
