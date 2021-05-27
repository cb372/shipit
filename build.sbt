organization := "com.ovoenergy"
scalaVersion := "2.12.10"
scalacOptions += "-Ypartial-unification"

// The version number is fixed, for the sake of simpler deployment scripts.
// The uploaded docker image will be tagged with the git sha1.
version := "1.0"

resolvers += Resolver.bintrayRepo("ovotech", "maven")
val elastic4sVersion = "7.8.1"
val circeVersion = "0.14.1"
val cirisVersion = "1.0.4"

libraryDependencies ++= Seq(
  ws,
  filters,
  "io.circe"                      %% "circe-parser"                   % circeVersion,
  "io.circe"                      %% "circe-generic"                  % circeVersion,
  "com.typesafe.akka"             %% "akka-slf4j"                     % "2.5.26",
  "org.typelevel"                 %% "cats-core"                      % "2.1.0",
  "com.gu.play-googleauth"        %% "play-v27"                       % "1.0.6",
  "vc.inreach.aws"                % "aws-signing-request-interceptor" % "0.0.22",
  "com.amazonaws"                 % "aws-java-sdk-core"               % "1.11.657",
  "me.moocar"                     % "logback-gelf"                    % "0.2",
  "is.cir"                        %% "ciris"                          % cirisVersion,
  "com.ovoenergy"                 %% "ciris-aws-ssm"                  % "1.0.0",
  "org.scalatest"                 %% "scalatest"                      % "3.1.0" % Test,
  "com.github.alexarchambault"    %% "scalacheck-shapeless_1.14"      % "1.2.3" % Test,
  "org.scalacheck"                %% "scalacheck"                     % "1.14.2" % Test,
  "com.sksamuel.elastic4s"        %% "elastic4s-client-esjava"        % elastic4sVersion,
  "com.sksamuel.elastic4s"        %% "elastic4s-effect-cats"          % elastic4sVersion,
  "com.sksamuel.elastic4s"        %% "elastic4s-json-circe"           % elastic4sVersion,
  "org.typelevel"                 %% "cats-tagless-macros"            % "0.12"
)

enablePlugins(PlayScala, DockerPlugin)
scalafmtOnCompile in ThisBuild := true

// Docker packaging stuff
dockerBaseImage := "adoptopenjdk:11-jdk-hotspot"
javaOptions in Universal ++= Seq(
  // -J params will be added as jvm parameters
  "-J-Xmx512m",
  "-J-Xms512m"
)
dockerUpdateLatest := false
