
name := "spark-performance-tuning"

version := "0.3"

scalaVersion := "2.12.20"

val sparkVersion = "3.5.5"
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
  "org.apache.spark" %% "spark-mllib" % sparkVersion, // needed for Vector data type
  "com.google.cloud.spark" %% "spark-bigquery-with-dependencies" % "0.42.4",
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