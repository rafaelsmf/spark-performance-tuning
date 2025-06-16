package challenge

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{count, countDistinct}

object WebContentAnalytics {

  def getSparkSession(awsAccessKey: String, awsSecretKey: String) = {
    val spark = SparkSession.builder()
      .appName("Web Content Analytics")
      //    .config("spark.hadoop.parquet.enable.summary-metadata", "false")
      //    .config("spark.sql.parquet.mergeSchema", "false")
      //    .config("spark.sql.parquet.filterPushdown", "true")
      //    .config("spark.sql.hive.metastorePartitionPruning", "true")
      .config("spark.hadoop.fs.s3a.access.key", awsAccessKey)
      .config("spark.hadoop.fs.s3a.secret.key", awsSecretKey)
      .config("spark.hadoop.fs.s3a.endpoint", "s3.amazonaws.com")
      .getOrCreate()
    spark
  }

  def readData(spark: SparkSession) = {
    /*
      root
       |-- url_surtkey: string (nullable = true)
       |-- url: string (nullable = true)
       |-- url_host_name: string (nullable = true)
       |-- url_host_tld: string (nullable = true)
       |-- url_host_2nd_last_part: string (nullable = true)
       |-- url_host_3rd_last_part: string (nullable = true)
       |-- url_host_4th_last_part: string (nullable = true)
       |-- url_host_5th_last_part: string (nullable = true)
       |-- url_host_registry_suffix: string (nullable = true)
       |-- url_host_registered_domain: string (nullable = true)
       |-- url_host_private_suffix: string (nullable = true)
       |-- url_host_private_domain: string (nullable = true)
       |-- url_host_name_reversed: string (nullable = true)
       |-- url_protocol: string (nullable = true)
       |-- url_port: integer (nullable = true)
       |-- url_path: string (nullable = true)
       |-- url_query: string (nullable = true)
       |-- fetch_time: timestamp (nullable = true)
       |-- fetch_status: short (nullable = true)
       |-- fetch_redirect: string (nullable = true)
       |-- content_digest: string (nullable = true)
       |-- content_mime_type: string (nullable = true)
       |-- content_mime_detected: string (nullable = true)
       |-- content_charset: string (nullable = true)
       |-- content_languages: string (nullable = true)
       |-- content_truncated: string (nullable = true)
       |-- warc_filename: string (nullable = true)
       |-- warc_record_offset: integer (nullable = true)
       |-- warc_record_length: integer (nullable = true)
       |-- warc_segment: string (nullable = true)
     */
    val sourcePath = "s3a://commoncrawl/cc-index/table/cc-main/warc/crawl=CC-MAIN-2025-18/subset=warc/"

    val warcDF = spark.read
      .option("inferSchema", "true")
      .format("parquet")
      .load(sourcePath)
    warcDF
  }

  def getTargetDomains(rawData: DataFrame, spark: SparkSession) = {
    import spark.implicits._

    val targetDF = rawData
      .where( ($"fetch_status" === 200)
        && ($"url_host_tld".isin("com", "br", "org"))
        && ($"content_mime_type" === "text/html")
      )
      .select(
        $"url",
        $"url_host_tld".as("domain"),
        $"url_host_name",
        $"url_host_registered_domain",
        $"fetch_time".as("crawl_timestamp"),
        $"content_mime_type",
        $"content_languages",
        $"warc_record_length".as("record_size_bytes")
      )
    targetDF
  }

  def getMostPopularByDomain(targetDF: DataFrame, spark: SparkSession) = {
    import spark.implicits._

    targetDF
      .groupBy($"domain")
      .agg(
        count($"url").as("total_pages"),
        countDistinct($"url_host_name").as("total_hosts"),
        countDistinct($"url_host_registered_domain").as("total_domains")
      )
      .orderBy($"total_pages".desc_nulls_last)
    targetDF
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Invalid Arguments: you must pass <awsAccessKey> <awsSecretKey> arguments")
      sys.exit(1)
    }
    val awsAccessKey = args(0)
    val awsSecretKey = args(1)
    val spark = getSparkSession(awsAccessKey, awsSecretKey)
    val warcDF = readData(spark)
    val targetDF = getTargetDomains(warcDF, spark)
    val mostPopularDF = getMostPopularByDomain(targetDF, spark)

    val destPath = "s3a://<your-bucket-path-to-save-the-data>"
    mostPopularDF.write
      .format("parquet")
      .mode("overwrite")
      .partitionBy("domain")
      .save(destPath)

    spark.stop()
  }
}
