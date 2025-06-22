package challenge

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{aggregate, coalesce, explode, expr, lit, lower, round, size, split}

object GithubReposAnalytics {

  val spark = SparkSession.builder()
    .appName("Github Repos Analytics")
    .getOrCreate()

  import spark.implicits._

  def getScalaRepos(billedProjectID: String, credentialsFilePath: String, temporaryGcsBucket: String) = {
    val githubReposLanguages = "bigquery-public-data.github_repos.languages"

    val scalaReposDF = spark.read
      .format("bigquery")
      .option("parentProject", billedProjectID)
      .option("credentialsFile", credentialsFilePath)
      .option("temporaryGcsBucket", temporaryGcsBucket)
      .option("table", githubReposLanguages)
      .load()
      .withColumn("total_bytes",
        aggregate(
          $"language",
          lit(0L),
          (acc, x) => acc + coalesce(x.getItem("bytes"), lit(0L))
        )
      ).withColumn("languages", explode($"language"))
      .withColumn("language_rate", round($"languages.bytes" / $"total_bytes", 2))
      .where(
        (lower($"languages.name") === "scala") // just repos that have scala as one of the languages present
        && ($"language_rate" > 0.5) // more than 50% of the code at repo implemented in Scala
        && ($"languages.bytes" >= 1024) // at least 1kb of Scala code
      )
      .select($"repo_name")

    scalaReposDF
  }

  def getWatchedRepos(billedProjectID: String, credentialsFilePath: String, temporaryGcsBucket: String) = {
    val githubSampleRepos = "bigquery-public-data.github_repos.sample_repos"

    val watchedReposDF = spark.read
      .format("bigquery")
      .option("parentProject", billedProjectID)
      .option("credentialsFile", credentialsFilePath)
      .option("temporaryGcsBucket", temporaryGcsBucket)
      .option("table", githubSampleRepos)
      .load()
      .where($"watch_count" >= 10) // at least 10 people watching the repo

    watchedReposDF
  }

  def getScalaCode(billedProjectID: String, credentialsFilePath: String, temporaryGcsBucket: String) = {
    val githubSampleContents = "bigquery-public-data.github_repos.sample_contents"

    val scalaCodeDF = spark.read
      .format("bigquery")
      .option("parentProject", billedProjectID)
      .option("credentialsFile", credentialsFilePath)
      .option("temporaryGcsBucket", temporaryGcsBucket)
      .option("table", githubSampleContents)
      .load()
      .where(
        ($"binary" === false)
        && ($"copies" === 1)
        && ($"sample_ref" === "refs/heads/master")
        && ($"sample_path".contains(".scala"))
      )
      .select(
        $"size",
        $"content",
        $"sample_repo_name",
        $"sample_path"
      )

    scalaCodeDF
  }

  def getTop20MostPopularScalaRepos(billedProjectID: String, credentialsFilePath: String, temporaryGcsBucket: String) = {
    val scalaReposDF = getScalaRepos(billedProjectID, credentialsFilePath, temporaryGcsBucket)
    val watchedReposDF = getWatchedRepos(billedProjectID, credentialsFilePath, temporaryGcsBucket)
    val sparkCodeDF = getScalaCode(billedProjectID, credentialsFilePath, temporaryGcsBucket)

    val top100ScalaReposDF = scalaReposDF.join(watchedReposDF)
      .orderBy($"watch_count".desc_nulls_last)
      .limit(20)
      .cache()
      .join(sparkCodeDF, sparkCodeDF("sample_repo_name") === scalaReposDF("repo_name"))
      .select(
        $"sample_repo_name".as("repo"),
        $"content",
        $"sample_path".as("path"),
        $"size"
      )

    top100ScalaReposDF
  }

  def getLibrariesByRepo(reposContentsDF: DataFrame) = {
    val librariesDF = reposContentsDF
      .withColumn("content_rows", split($"content", "\n"))
      .withColumn("import_rows", expr("filter(content_rows, row -> row rlike '^import ')"))
      .withColumn(
        "libraries",
        expr("array_distinct(transform(import_rows, x -> regexp_extract(x, 'import ([a-zA-Z0-9._]+)(?:\\\\.[A-Z]|\\\\{|\\\\_|$)', 1)))")
      )
      .where(size($"libraries") > 0)
      .select($"repo", $"libraries")

    librariesDF
  }

  def getTop5Libraries(librariesDF: DataFrame) = {
    val top5LibrariesDF = librariesDF
      .withColumn("library", explode($"libraries"))
      .groupBy($"library")
      .count()
      .orderBy($"count".desc_nulls_last)
      .limit(5)

    top5LibrariesDF
  }

  def stopSpark() = spark.stop()

  def main(args: Array[String]): Unit = {
    val billedProjectID = args(0)
    val credentialsFilePath = args(1)
    val temporaryGcsBucket = args(2)

    val top20ScalaReposDF = getTop20MostPopularScalaRepos(
      billedProjectID,
      credentialsFilePath,
      temporaryGcsBucket
    ).cache()
    top20ScalaReposDF.show()

    val librariesDF = getLibrariesByRepo(top20ScalaReposDF).cache()
    librariesDF.show()

    val top5LibrariesDF = getTop5Libraries(librariesDF)
    top5LibrariesDF.show()

    stopSpark()

  }
}
