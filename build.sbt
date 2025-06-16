
name := "spark-performance-tuning"

version := "0.3"

scalaVersion := "2.13.16"

val sparkVersion = "4.0.0"
val log4jVersion = "2.24.3"
val zstdJniVersion = "1.5.6-5"

resolvers ++= Seq(
  "spark-packages" at "https://repos.spark-packages.org",
  "Typesafe Simple Repository" at "https://repo.typesafe.com/typesafe/simple/maven-releases",
  "MavenRepository" at "https://mvnrepository.com"
)

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.hadoop" % "hadoop-aws" % "3.4.1",
  // logging
  "org.apache.logging.log4j" % "log4j-api" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % log4jVersion,
)

dependencyOverrides += "com.github.luben" % "zstd-jni" % zstdJniVersion

fork := true

javaOptions ++= Seq(
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
)

Test / javaOptions ++= javaOptions.value