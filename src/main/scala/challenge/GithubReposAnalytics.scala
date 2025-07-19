package challenge

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{aggregate, coalesce, explode, expr, lit, lower, round, size, split, col, count, sum, avg, max, min, when, desc, asc, collect_list, array_distinct, regexp_extract, regexp_replace, length, substring, instr, trim, upper, concat, concat_ws, year, month, dayofmonth, unix_timestamp, from_unixtime, datediff, current_date, monotonically_increasing_id, row_number, rank, dense_rank, lag, lead, first, last, stddev, variance, percentile_approx, collect_set, map_from_arrays, map_keys, map_values, flatten, array_union, array_intersect, array_except, sort_array, reverse, slice, element_at, cardinality, arrays_zip, transform, filter, exists, forall, array_contains, array_position, array_remove, array_repeat, sequence, shuffle, zip_with}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.types.{StructType, StructField, StringType, IntegerType, LongType, DoubleType, BooleanType, TimestampType}

object GithubReposAnalytics {

  val spark = SparkSession.builder()
    .appName("Github Repos Analytics - Performance Challenge")
    .config("spark.sql.adaptive.enabled", "false") // Disable AQE for performance challenge
    .config("spark.sql.adaptive.coalescePartitions.enabled", "false")
    .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
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
      .withColumn("repo_id", monotonically_increasing_id()) // Add unique ID for each repo
      .withColumn("scala_complexity_score", 
        when($"languages.bytes" > 100000, 5)
        .when($"languages.bytes" > 50000, 4)
        .when($"languages.bytes" > 10000, 3)
        .when($"languages.bytes" > 5000, 2)
        .otherwise(1)
      )
      .where(
        (lower($"languages.name") === "scala") // just repos that have scala as one of the languages present
        && ($"language_rate" > 0.3) // reduced threshold to get more repos
        && ($"languages.bytes" >= 512) // reduced threshold to get more repos
      )
      .select($"repo_name", $"repo_id", $"language_rate", $"languages.bytes".as("scala_bytes"), $"total_bytes", $"scala_complexity_score")

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
      .withColumn("popularity_score", 
        ($"watch_count" * 0.4) + ($"fork_count" * 0.3) + ($"size" * 0.0001)
      )
      .withColumn("created_year", year(from_unixtime($"created_at")))
      .withColumn("updated_year", year(from_unixtime($"updated_at")))
      .withColumn("repo_age_days", datediff(current_date(), from_unixtime($"created_at")))
      .withColumn("days_since_last_update", datediff(current_date(), from_unixtime($"updated_at")))
      .withColumn("activity_category",
        when($"days_since_last_update" <= 30, "very_active")
        .when($"days_since_last_update" <= 90, "active")
        .when($"days_since_last_update" <= 365, "moderate")
        .otherwise("inactive")
      )
      .where($"watch_count" >= 5) // reduced threshold to get more repos

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
      .withColumn("file_extension", regexp_extract($"sample_path", "\\.(\\w+)$", 1))
      .withColumn("file_name", regexp_extract($"sample_path", "([^/]+)$", 1))
      .withColumn("directory_depth", size(split($"sample_path", "/")) - 1)
      .withColumn("is_test_file", $"sample_path".contains("test"))
      .withColumn("is_main_file", $"sample_path".contains("main"))
      .withColumn("content_length", length($"content"))
      .withColumn("lines_count", size(split($"content", "\n")))
      .withColumn("code_density", round($"content_length" / $"lines_count", 2))
      .select(
        $"size",
        $"content",
        $"sample_repo_name",
        $"sample_path",
        $"file_extension",
        $"file_name",
        $"directory_depth",
        $"is_test_file",
        $"is_main_file",
        $"content_length",
        $"lines_count",
        $"code_density"
      )

    scalaCodeDF
  }

  def getTop50MostPopularScalaRepos(billedProjectID: String, credentialsFilePath: String, temporaryGcsBucket: String) = {
    val scalaReposDF = getScalaRepos(billedProjectID, credentialsFilePath, temporaryGcsBucket)
    val watchedReposDF = getWatchedRepos(billedProjectID, credentialsFilePath, temporaryGcsBucket)
    val scalaCodeDF = getScalaCode(billedProjectID, credentialsFilePath, temporaryGcsBucket)

    // Complex join with multiple conditions and transformations
    val enrichedReposDF = scalaReposDF
      .join(watchedReposDF, scalaReposDF("repo_name") === watchedReposDF("repo_name"), "inner")
      .withColumn("combined_score", 
        ($"popularity_score" * 0.5) + ($"scala_complexity_score" * 0.3) + ($"language_rate" * 0.2)
      )
      .withColumn("repo_rank", row_number().over(Window.orderBy($"combined_score".desc)))
      .where($"repo_rank" <= 100) // Get top 100 first
      .orderBy($"combined_score".desc)
      .limit(50)

    // Join with code content (this creates a cartesian-like join that's expensive)
    val finalDF = enrichedReposDF
      .join(scalaCodeDF, enrichedReposDF("repo_name") === scalaCodeDF("sample_repo_name"), "inner")
      .select(
        enrichedReposDF("repo_name").as("repo"),
        $"content",
        $"sample_path".as("path"),
        $"size",
        $"file_name",
        $"directory_depth",
        $"is_test_file",
        $"is_main_file",
        $"content_length",
        $"lines_count",
        $"code_density",
        $"combined_score",
        $"popularity_score",
        $"scala_complexity_score",
        $"language_rate",
        $"activity_category",
        $"repo_age_days",
        $"days_since_last_update"
      )

    finalDF
  }

  def getLibrariesByRepo(reposContentsDF: DataFrame) = {
    val librariesDF = reposContentsDF
      .withColumn("content_rows", split($"content", "\n"))
      .withColumn("import_rows", expr("filter(content_rows, row -> row rlike '^import ')"))
      .withColumn("package_rows", expr("filter(content_rows, row -> row rlike '^package ')"))
      .withColumn(
        "libraries",
        expr("array_distinct(transform(import_rows, x -> regexp_extract(x, 'import ([a-zA-Z0-9._]+)(?:\\\\.[A-Z]|\\\\{|\\\\_|$)', 1)))")
      )
      .withColumn(
        "packages",
        expr("array_distinct(transform(package_rows, x -> regexp_extract(x, 'package ([a-zA-Z0-9._]+)', 1)))")
      )
      .withColumn("library_count", size($"libraries"))
      .withColumn("package_count", size($"packages"))
      .withColumn("has_akka", array_contains($"libraries", "akka"))
      .withColumn("has_spark", array_contains($"libraries", "org.apache.spark"))
      .withColumn("has_cats", array_contains($"libraries", "cats"))
      .withColumn("has_scalaz", array_contains($"libraries", "scalaz"))
      .withColumn("has_play", array_contains($"libraries", "play"))
      .where(size($"libraries") > 0)
      .select(
        $"repo", 
        $"path",
        $"libraries", 
        $"packages",
        $"library_count",
        $"package_count",
        $"has_akka",
        $"has_spark",
        $"has_cats",
        $"has_scalaz",
        $"has_play",
        $"content_length",
        $"lines_count",
        $"code_density"
      )

    librariesDF
  }

  def getTop10Libraries(librariesDF: DataFrame) = {
    val top10LibrariesDF = librariesDF
      .withColumn("library", explode($"libraries"))
      .filter($"library" =!= "") // Remove empty libraries
      .groupBy($"library")
      .agg(
        count("*").as("usage_count"),
        countDistinct($"repo").as("repo_count"),
        avg($"content_length").as("avg_content_length"),
        avg($"lines_count").as("avg_lines_count"),
        avg($"code_density").as("avg_code_density"),
        sum(when($"has_akka", 1).otherwise(0)).as("akka_repos"),
        sum(when($"has_spark", 1).otherwise(0)).as("spark_repos"),
        sum(when($"has_cats", 1).otherwise(0)).as("cats_repos"),
        sum(when($"has_scalaz", 1).otherwise(0)).as("scalaz_repos"),
        sum(when($"has_play", 1).otherwise(0)).as("play_repos")
      )
      .withColumn("popularity_score", ($"usage_count" * 0.6) + ($"repo_count" * 0.4))
      .orderBy($"popularity_score".desc)
      .limit(10)

    top10LibrariesDF
  }

  // New complex analysis functions that create performance bottlenecks
  def getCodeComplexityAnalysis(reposContentsDF: DataFrame) = {
    val complexityDF = reposContentsDF
      .withColumn("content_rows", split($"content", "\n"))
      .withColumn("class_definitions", expr("filter(content_rows, row -> row rlike '\\\\s*class\\\\s+\\\\w+')"))
      .withColumn("object_definitions", expr("filter(content_rows, row -> row rlike '\\\\s*object\\\\s+\\\\w+')"))
      .withColumn("trait_definitions", expr("filter(content_rows, row -> row rlike '\\\\s*trait\\\\s+\\\\w+')"))
      .withColumn("def_definitions", expr("filter(content_rows, row -> row rlike '\\\\s*def\\\\s+\\\\w+')"))
      .withColumn("val_definitions", expr("filter(content_rows, row -> row rlike '\\\\s*val\\\\s+\\\\w+')"))
      .withColumn("var_definitions", expr("filter(content_rows, row -> row rlike '\\\\s*var\\\\s+\\\\w+')"))
      .withColumn("for_loops", expr("filter(content_rows, row -> row rlike '\\\\s*for\\\\s*\\\\(')"))
      .withColumn("while_loops", expr("filter(content_rows, row -> row rlike '\\\\s*while\\\\s*\\\\(')"))
      .withColumn("if_statements", expr("filter(content_rows, row -> row rlike '\\\\s*if\\\\s*\\\\(')"))
      .withColumn("match_statements", expr("filter(content_rows, row -> row rlike '\\\\s*match\\\\s*\\\\{')"))
      .withColumn("try_catch", expr("filter(content_rows, row -> row rlike '\\\\s*try\\\\s*\\\\{')"))
      .withColumn("lambda_functions", expr("filter(content_rows, row -> row rlike '=>')"))
      .withColumn("complexity_score", 
        size($"class_definitions") * 3 + 
        size($"object_definitions") * 2 + 
        size($"trait_definitions") * 2 + 
        size($"def_definitions") * 1 + 
        size($"for_loops") * 2 + 
        size($"while_loops") * 2 + 
        size($"if_statements") * 1 + 
        size($"match_statements") * 2 + 
        size($"try_catch") * 1 + 
        size($"lambda_functions") * 1
      )
      .withColumn("complexity_category",
        when($"complexity_score" >= 100, "very_high")
        .when($"complexity_score" >= 50, "high")
        .when($"complexity_score" >= 20, "medium")
        .when($"complexity_score" >= 5, "low")
        .otherwise("very_low")
      )
      .select(
        $"repo",
        $"path",
        $"complexity_score",
        $"complexity_category",
        $"content_length",
        $"lines_count",
        size($"class_definitions").as("class_count"),
        size($"object_definitions").as("object_count"),
        size($"trait_definitions").as("trait_count"),
        size($"def_definitions").as("method_count"),
        size($"val_definitions").as("val_count"),
        size($"var_definitions").as("var_count"),
        size($"for_loops").as("for_loop_count"),
        size($"while_loops").as("while_loop_count"),
        size($"if_statements").as("if_count"),
        size($"match_statements").as("match_count"),
        size($"try_catch").as("try_catch_count"),
        size($"lambda_functions").as("lambda_count")
      )

    complexityDF
  }

  def getRepoStatistics(reposContentsDF: DataFrame) = {
    val window = Window.partitionBy($"repo")
    
    val repoStatsDF = reposContentsDF
      .withColumn("repo_file_count", count("*").over(window))
      .withColumn("repo_total_lines", sum($"lines_count").over(window))
      .withColumn("repo_total_content_length", sum($"content_length").over(window))
      .withColumn("repo_avg_code_density", avg($"code_density").over(window))
      .withColumn("repo_max_file_size", max($"content_length").over(window))
      .withColumn("repo_min_file_size", min($"content_length").over(window))
      .withColumn("repo_file_size_stddev", stddev($"content_length").over(window))
      .withColumn("repo_avg_directory_depth", avg($"directory_depth").over(window))
      .withColumn("repo_test_file_ratio", 
        sum(when($"is_test_file", 1).otherwise(0)).over(window) / count("*").over(window)
      )
      .withColumn("repo_main_file_ratio", 
        sum(when($"is_main_file", 1).otherwise(0)).over(window) / count("*").over(window)
      )
      .groupBy($"repo")
      .agg(
        first($"repo_file_count").as("file_count"),
        first($"repo_total_lines").as("total_lines"),
        first($"repo_total_content_length").as("total_content_length"),
        first($"repo_avg_code_density").as("avg_code_density"),
        first($"repo_max_file_size").as("max_file_size"),
        first($"repo_min_file_size").as("min_file_size"),
        first($"repo_file_size_stddev").as("file_size_stddev"),
        first($"repo_avg_directory_depth").as("avg_directory_depth"),
        first($"repo_test_file_ratio").as("test_file_ratio"),
        first($"repo_main_file_ratio").as("main_file_ratio")
      )
      .withColumn("repo_size_category",
        when($"total_content_length" >= 1000000, "very_large")
        .when($"total_content_length" >= 500000, "large")
        .when($"total_content_length" >= 100000, "medium")
        .when($"total_content_length" >= 10000, "small")
        .otherwise("very_small")
      )

    repoStatsDF
  }

  def getLibraryCoOccurrenceAnalysis(librariesDF: DataFrame) = {
    // This creates a very expensive cartesian product for co-occurrence analysis
    val libraryPairsDF = librariesDF
      .select($"repo", explode($"libraries").as("library1"))
      .join(
        librariesDF.select($"repo", explode($"libraries").as("library2")),
        Seq("repo")
      )
      .where($"library1" < $"library2") // Avoid duplicate pairs and self-pairs
      .groupBy($"library1", $"library2")
      .agg(
        count("*").as("co_occurrence_count"),
        countDistinct($"repo").as("repo_count")
      )
      .withColumn("co_occurrence_score", $"co_occurrence_count" * $"repo_count")
      .orderBy($"co_occurrence_score".desc)
      .limit(20)

    libraryPairsDF
  }

  def getAdvancedRepoRanking(reposContentsDF: DataFrame, librariesDF: DataFrame) = {
    val complexityDF = getCodeComplexityAnalysis(reposContentsDF)
    val repoStatsDF = getRepoStatistics(reposContentsDF)
    
    // Multiple expensive joins and aggregations
    val libraryStatsDF = librariesDF
      .groupBy($"repo")
      .agg(
        avg($"library_count").as("avg_library_count"),
        max($"library_count").as("max_library_count"),
        sum(when($"has_akka", 1).otherwise(0)).as("akka_files"),
        sum(when($"has_spark", 1).otherwise(0)).as("spark_files"),
        sum(when($"has_cats", 1).otherwise(0)).as("cats_files"),
        sum(when($"has_scalaz", 1).otherwise(0)).as("scalaz_files"),
        sum(when($"has_play", 1).otherwise(0)).as("play_files"),
        countDistinct(explode($"libraries")).as("unique_libraries")
      )

    val complexityStatsDF = complexityDF
      .groupBy($"repo")
      .agg(
        avg($"complexity_score").as("avg_complexity"),
        max($"complexity_score").as("max_complexity"),
        sum($"complexity_score").as("total_complexity"),
        stddev($"complexity_score").as("complexity_stddev"),
        sum(when($"complexity_category" === "very_high", 1).otherwise(0)).as("very_high_complexity_files"),
        sum(when($"complexity_category" === "high", 1).otherwise(0)).as("high_complexity_files"),
        avg($"method_count").as("avg_method_count"),
        avg($"class_count").as("avg_class_count"),
        sum($"lambda_count").as("total_lambda_count")
      )

    // Final expensive join of all statistics
    val finalRankingDF = repoStatsDF
      .join(libraryStatsDF, Seq("repo"), "left")
      .join(complexityStatsDF, Seq("repo"), "left")
      .withColumn("technical_sophistication_score",
        coalesce($"avg_complexity", lit(0)) * 0.3 +
        coalesce($"unique_libraries", lit(0)) * 0.2 +
        coalesce($"total_lambda_count", lit(0)) * 0.1 +
        coalesce($"avg_method_count", lit(0)) * 0.1 +
        coalesce($"avg_class_count", lit(0)) * 0.1 +
        coalesce($"test_file_ratio", lit(0)) * 100 * 0.2
      )
      .withColumn("repo_quality_score",
        coalesce($"technical_sophistication_score", lit(0)) * 0.4 +
        coalesce($"file_count", lit(0)) * 0.1 +
        coalesce($"total_lines", lit(0)) * 0.0001 +
        coalesce($"avg_code_density", lit(0)) * 0.3 +
        (1 - coalesce($"complexity_stddev", lit(0)) * 0.01) * 0.2
      )
      .orderBy($"repo_quality_score".desc)

    finalRankingDF
  }

  def stopSpark() = spark.stop()

  def main(args: Array[String]): Unit = {
    val billedProjectID = args(0)
    val credentialsFilePath = args(1)
    val temporaryGcsBucket = args(2)

    println("=== Starting Github Repos Analytics - Performance Challenge ===")
    
    // Step 1: Get top 50 popular Scala repositories with enriched data
    println("Step 1: Fetching top 50 popular Scala repositories...")
    val top50ScalaReposDF = getTop50MostPopularScalaRepos(
      billedProjectID,
      credentialsFilePath,
      temporaryGcsBucket
    )
    
    // Expensive caching operation - cache large dataset
    println("Caching repository data...")
    top50ScalaReposDF.cache()
    
    // Force computation to populate cache
    println(s"Total files found: ${top50ScalaReposDF.count()}")
    
    // Step 2: Extract libraries with enhanced analysis
    println("Step 2: Analyzing libraries and dependencies...")
    val librariesDF = getLibrariesByRepo(top50ScalaReposDF)
    librariesDF.cache()
    
    println(s"Files with libraries: ${librariesDF.count()}")
    librariesDF.show(10, truncate = false)

    // Step 3: Get top 10 libraries with detailed statistics
    println("Step 3: Computing top 10 libraries...")
    val top10LibrariesDF = getTop10Libraries(librariesDF)
    top10LibrariesDF.show(10, truncate = false)

    // Step 4: Perform complex code analysis
    println("Step 4: Performing code complexity analysis...")
    val complexityDF = getCodeComplexityAnalysis(top50ScalaReposDF)
    complexityDF.cache()
    
    println("Code complexity statistics:")
    complexityDF.groupBy($"complexity_category")
      .agg(
        count("*").as("file_count"),
        avg($"complexity_score").as("avg_score"),
        max($"complexity_score").as("max_score")
      )
      .orderBy($"avg_score".desc)
      .show()

    // Step 5: Generate repository statistics
    println("Step 5: Computing repository statistics...")
    val repoStatsDF = getRepoStatistics(top50ScalaReposDF)
    repoStatsDF.cache()
    
    println("Repository size distribution:")
    repoStatsDF.groupBy($"repo_size_category")
      .agg(
        count("*").as("repo_count"),
        avg($"total_lines").as("avg_lines"),
        avg($"file_count").as("avg_files")
      )
      .orderBy($"avg_lines".desc)
      .show()

    // Step 6: Library co-occurrence analysis (very expensive)
    println("Step 6: Analyzing library co-occurrence patterns...")
    val coOccurrenceDF = getLibraryCoOccurrenceAnalysis(librariesDF)
    
    println("Top library combinations:")
    coOccurrenceDF.show(10, truncate = false)

    // Step 7: Advanced repository ranking
    println("Step 7: Computing advanced repository rankings...")
    val advancedRankingDF = getAdvancedRepoRanking(top50ScalaReposDF, librariesDF)
    advancedRankingDF.cache()
    
    println("Top 10 repositories by quality score:")
    advancedRankingDF.select(
      $"repo",
      $"repo_quality_score",
      $"technical_sophistication_score",
      $"file_count",
      $"total_lines",
      $"unique_libraries",
      $"avg_complexity",
      $"test_file_ratio"
    ).show(10, truncate = false)

    // Step 8: Cross-analysis between different metrics
    println("Step 8: Performing cross-analysis...")
    val crossAnalysisDF = top50ScalaReposDF
      .join(complexityDF, Seq("repo", "path"), "inner")
      .join(librariesDF, Seq("repo", "path"), "inner")
      .join(repoStatsDF, Seq("repo"), "inner")
      .join(advancedRankingDF, Seq("repo"), "inner")
      .withColumn("file_rank", 
        row_number().over(Window.partitionBy($"repo").orderBy($"complexity_score".desc))
      )
      .where($"file_rank" <= 3) // Top 3 most complex files per repo
      .select(
        $"repo",
        $"path",
        $"complexity_score",
        $"complexity_category",
        $"library_count",
        $"content_length",
        $"lines_count",
        $"repo_quality_score",
        $"technical_sophistication_score",
        $"file_rank"
      )
      .orderBy($"repo_quality_score".desc, $"file_rank".asc)

    println("Most complex files in top repositories:")
    crossAnalysisDF.show(30, truncate = false)

    // Step 9: Generate summary report with multiple aggregations
    println("Step 9: Generating comprehensive summary report...")
    val summaryDF = spark.sql(s"""
      SELECT 
        'Total Repositories' as metric,
        COUNT(DISTINCT repo) as value,
        '' as category
      FROM global_temp.repos_summary
      UNION ALL
      SELECT 
        'Total Files' as metric,
        COUNT(*) as value,
        '' as category
      FROM global_temp.repos_summary
      UNION ALL
      SELECT 
        'Average Complexity' as metric,
        ROUND(AVG(complexity_score), 2) as value,
        complexity_category as category
      FROM global_temp.repos_summary
      GROUP BY complexity_category
      UNION ALL
      SELECT 
        'Most Used Library' as metric,
        0 as value,
        library as category
      FROM (
        SELECT library, COUNT(*) as usage_count
        FROM global_temp.libraries_summary
        GROUP BY library
        ORDER BY usage_count DESC
        LIMIT 1
      )
      ORDER BY metric, category
    """)

    // Create temporary views for SQL analysis
    crossAnalysisDF.createOrReplaceGlobalTempView("repos_summary")
    librariesDF.select($"repo", explode($"libraries").as("library")).createOrReplaceGlobalTempView("libraries_summary")
    
    summaryDF.show(20, truncate = false)

    // Step 10: Final cleanup and performance metrics
    println("Step 10: Cleaning up and showing performance metrics...")
    
    // Force one more expensive operation to stress the system
    val finalStatsDF = top50ScalaReposDF
      .groupBy($"repo")
      .agg(
        count("*").as("total_files"),
        sum($"content_length").as("total_content_length"),
        avg($"code_density").as("avg_code_density"),
        collect_set($"path").as("all_paths"),
        collect_list($"file_name").as("all_file_names")
      )
      .withColumn("path_count", size($"all_paths"))
      .withColumn("unique_file_names", size(array_distinct($"all_file_names")))
      .withColumn("duplicate_file_names", size($"all_file_names") - size(array_distinct($"all_file_names")))
      .select(
        $"repo",
        $"total_files",
        $"total_content_length",
        $"avg_code_density",
        $"path_count",
        $"unique_file_names",
        $"duplicate_file_names"
      )
      .orderBy($"total_content_length".desc)

    println("Final repository statistics:")
    finalStatsDF.show(20, truncate = false)

    println("=== Analysis Complete ===")
    println(s"Processed ${finalStatsDF.count()} repositories")
    
    // Don't stop Spark immediately - let it run for monitoring
    // stopSpark()
  }
}
