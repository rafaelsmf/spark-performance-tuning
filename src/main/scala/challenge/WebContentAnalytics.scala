package challenge

import org.apache.spark.sql.{DataFrame, SparkSession, Row, SaveMode}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.storage.StorageLevel
import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.RDD

/**
 * Advanced Web Content Analytics Challenge
 * 
 * This challenge involves processing large volumes of web crawl data from Common Crawl
 * and joining it with additional datasets to perform complex analysis including:
 * - Content popularity analysis by domain, language, and time
 * - Host reputation scoring based on multiple metrics
 * - Time series analysis of content trends
 * - Content size distribution and outlier detection
 * - Geographic distribution of content
 * 
 * Two implementations are provided:
 * 1. A naive implementation with basic Spark operations
 * 2. An optimized implementation using various performance tuning techniques
 */
object WebContentAnalytics {

  def getSparkSession(awsAccessKey: String, awsSecretKey: String) = {
    val spark = SparkSession.builder()
      .appName("Advanced Web Content Analytics")
      .config("spark.hadoop.fs.s3a.access.key", awsAccessKey)
      .config("spark.hadoop.fs.s3a.secret.key", awsSecretKey)
      .config("spark.hadoop.fs.s3a.endpoint", "s3.amazonaws.com")
      // Only for optimized version
      // .config("spark.sql.adaptive.enabled", "true")
      // .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
      // .config("spark.sql.shuffle.partitions", "2000")
      // .config("spark.default.parallelism", "1000")
      // .config("spark.memory.fraction", "0.8")
      // .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      // .config("spark.kryoserializer.buffer.max", "1g")
      // .config("spark.sql.autoBroadcastJoinThreshold", "100MB")
      // .config("spark.hadoop.parquet.enable.summary-metadata", "false")
      // .config("spark.sql.parquet.mergeSchema", "false")
      // .config("spark.sql.parquet.filterPushdown", "true")
      // .config("spark.sql.hive.metastorePartitionPruning", "true")
      .getOrCreate()
    spark
  }

  // Data sources
  def readWarcData(spark: SparkSession): DataFrame = {
    val sourcePath = "s3a://commoncrawl/cc-index/table/cc-main/warc/crawl=CC-MAIN-2025-18/subset=warc/"
    spark.read
      .option("inferSchema", "true")
      .format("parquet")
      .load(sourcePath)
  }
  
  def readDomainReputationData(spark: SparkSession): DataFrame = {
    val sourcePath = "s3a://your-bucket/domain-reputation/"
    spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(sourcePath)
  }
  
  def readGeoIpData(spark: SparkSession): DataFrame = {
    val sourcePath = "s3a://your-bucket/geo-ip-database/"
    spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .parquet(sourcePath)
  }
  
  def readContentCategoryData(spark: SparkSession): DataFrame = {
    val sourcePath = "s3a://your-bucket/content-categories/"
    spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .parquet(sourcePath)
  }
  
  // NAIVE IMPLEMENTATION
  object Naive {
    def processData(spark: SparkSession, awsAccessKey: String, awsSecretKey: String): Unit = {
      import spark.implicits._
      
      println("Starting naive implementation...")
      
      // Read all data
      val warcDF = readWarcData(spark)
      val domainReputationDF = readDomainReputationData(spark)
      val geoIpDF = readGeoIpData(spark)
      val contentCategoryDF = readContentCategoryData(spark)
      
      // Filter target domains
      val targetDF = warcDF
        .where($"fetch_status" === 200 && 
               $"url_host_tld".isin("com", "org", "net", "br", "de", "uk", "fr", "jp") && 
               $"content_mime_type" === "text/html")
        .select(
          $"url",
          $"url_host_tld".as("domain"),
          $"url_host_name",
          $"url_host_registered_domain".as("registered_domain"),
          $"fetch_time".as("crawl_timestamp"),
          $"content_mime_type",
          $"content_languages",
          $"warc_record_length".as("record_size_bytes"),
          $"content_charset",
          $"url_host_2nd_last_part".as("domain_second_part")
        )
      
      // Join with domain reputation
      val withReputationDF = targetDF.join(
        domainReputationDF,
        targetDF("registered_domain") === domainReputationDF("domain_name"),
        "left_outer"
      )
      
      // Join with GeoIP data
      val withGeoDF = withReputationDF.join(
        geoIpDF,
        withReputationDF("url_host_name") === geoIpDF("hostname"),
        "left_outer"
      )
      
      // Join with content categories
      val withCategoriesDF = withGeoDF.join(
        contentCategoryDF,
        withGeoDF("url") === contentCategoryDF("page_url"),
        "left_outer"
      )
      
      // Time-based aggregations - Content trends over time
      val timeWindow = Window.partitionBy($"domain", $"country")
        .orderBy($"crawl_hour")
        .rowsBetween(-6, 0)  // 7-hour moving average
      
      val contentTrendsDF = withCategoriesDF
        .withColumn("crawl_hour", date_format($"crawl_timestamp", "yyyy-MM-dd HH"))
        .groupBy($"domain", $"country", $"crawl_hour")
        .agg(
          count($"url").as("hourly_pages"),
          avg($"record_size_bytes").as("avg_page_size"),
          avg($"reputation_score").as("avg_reputation")
        )
        .withColumn("moving_avg_pages", avg($"hourly_pages").over(timeWindow))
        .orderBy($"domain", $"country", $"crawl_hour")
      
      // Domain popularity by country
      val domainPopularityDF = withCategoriesDF
        .groupBy($"domain", $"country")
        .agg(
          count($"url").as("total_pages"),
          countDistinct($"registered_domain").as("unique_domains"),
          countDistinct($"url_host_name").as("unique_hosts"),
          avg($"reputation_score").as("avg_reputation"),
          sum($"record_size_bytes").as("total_content_size")
        )
        .orderBy($"country", $"total_pages".desc)
      
      // Content size distribution
      val sizeDistributionDF = withCategoriesDF
        .withColumn("size_bucket", 
          when($"record_size_bytes" < 10000, "tiny")
          .when($"record_size_bytes" < 100000, "small")
          .when($"record_size_bytes" < 1000000, "medium")
          .otherwise("large")
        )
        .groupBy($"domain", $"content_category", $"size_bucket")
        .agg(
          count($"url").as("page_count"),
          avg($"record_size_bytes").as("avg_size"),
          min($"record_size_bytes").as("min_size"),
          max($"record_size_bytes").as("max_size")
        )
      
      // Language distribution
      val languageDistributionDF = withCategoriesDF
        .filter($"content_languages".isNotNull)
        .withColumn("language", explode(split($"content_languages", ",")))
        .groupBy($"domain", $"language")
        .agg(
          count($"url").as("page_count"),
          avg($"record_size_bytes").as("avg_size")
        )
        .orderBy($"domain", $"page_count".desc)
      
      // Save results to S3
      val destPath = "s3a://your-bucket/web-analytics-results/"
      
      contentTrendsDF.write
        .format("parquet")
        .mode("overwrite")
        .partitionBy("domain", "country")
        .save(destPath + "content-trends")
      
      domainPopularityDF.write
        .format("parquet")
        .mode("overwrite")
        .partitionBy("country")
        .save(destPath + "domain-popularity")
      
      sizeDistributionDF.write
        .format("parquet")
        .mode("overwrite")
        .partitionBy("domain", "content_category")
        .save(destPath + "size-distribution")
      
      languageDistributionDF.write
        .format("parquet")
        .mode("overwrite")
        .partitionBy("domain")
        .save(destPath + "language-distribution")
      
      println("Naive implementation completed")
    }
  }
  
  // OPTIMIZED IMPLEMENTATION
  object Optimized {
    def processData(spark: SparkSession, awsAccessKey: String, awsSecretKey: String): Unit = {
      import spark.implicits._
      
      println("Starting optimized implementation...")
      
      // Enable runtime optimizations
      spark.conf.set("spark.sql.adaptive.enabled", "true")
      spark.conf.set("spark.sql.adaptive.coalescePartitions.enabled", "true")
      spark.conf.set("spark.sql.shuffle.partitions", "2000")
      
      // Read and optimize main dataset with partition pruning
      val warcDF = spark.read
        .option("inferSchema", "true")
        .format("parquet")
        // Use partition pruning by specifying only the partitions we need
        .load("s3a://commoncrawl/cc-index/table/cc-main/warc/crawl=CC-MAIN-2025-18/subset=warc/")
        // Early projection to reduce data size
        .select(
          $"url", $"url_host_tld", $"url_host_name", $"url_host_registered_domain",
          $"fetch_time", $"fetch_status", $"content_mime_type", $"content_languages",
          $"warc_record_length", $"content_charset", $"url_host_2nd_last_part"
        )
        // Early filtering to reduce data size
        .filter($"fetch_status" === 200 && 
                $"url_host_tld".isin("com", "org", "net", "br", "de", "uk", "fr", "jp") && 
                $"content_mime_type" === "text/html")
      
      // Cache the filtered data with appropriate storage level
      val cachedWarcDF = warcDF.persist(StorageLevel.MEMORY_AND_DISK_SER)
      
      // Load small dimension tables and broadcast them
      val domainReputationDF = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .csv("s3a://your-bucket/domain-reputation/")
      
      val broadcastReputationDF = broadcast(domainReputationDF)
      
      // Load and optimize GeoIP data
      val geoIpDF = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .parquet("s3a://your-bucket/geo-ip-database/")
      
      // Repartition GeoIP data for better join performance
      val optimizedGeoDF = geoIpDF
        .repartition(200, $"hostname")
        .persist(StorageLevel.MEMORY_AND_DISK)
      
      // Load content category data with partition pruning
      val contentCategoryDF = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .parquet("s3a://your-bucket/content-categories/")
      
      // Prepare target data with salting to prevent skew
      val targetDF = cachedWarcDF
        .withColumnRenamed("url_host_tld", "domain")
        .withColumnRenamed("url_host_registered_domain", "registered_domain")
        .withColumnRenamed("fetch_time", "crawl_timestamp")
        .withColumnRenamed("warc_record_length", "record_size_bytes")
        .withColumnRenamed("url_host_2nd_last_part", "domain_second_part")
        // Add salt column for skewed keys
        .withColumn("salt", (rand() * 50).cast("int"))
      
      // Use broadcast join for domain reputation data
      val withReputationDF = targetDF.join(
        broadcastReputationDF,
        targetDF("registered_domain") === broadcastReputationDF("domain_name"),
        "left_outer"
      )
      
      // Optimize the GeoIP join with salting technique for skewed keys
      val saltedGeoDF = optimizedGeoDF.withColumn("salt", (rand() * 50).cast("int"))
      
      val withGeoDF = withReputationDF.join(
        saltedGeoDF,
        withReputationDF("url_host_name") === saltedGeoDF("hostname") && 
        withReputationDF("salt") === saltedGeoDF("salt"),
        "left_outer"
      ).drop($"salt")
      
      // Optimize content category join
      val withCategoriesDF = withGeoDF.join(
        broadcast(contentCategoryDF),
        withGeoDF("url") === contentCategoryDF("page_url"),
        "left_outer"
      )
      
      // Cache the final joined dataset with appropriate persistence level
      val processedDF = withCategoriesDF
        .withColumn("crawl_hour", date_format($"crawl_timestamp", "yyyy-MM-dd HH"))
        .persist(StorageLevel.MEMORY_AND_DISK_SER)
      
      // Convert to RDD for low-level optimizations on time-based computations
      val contentTrendsRDD: RDD[(String, String, String, Int, Double, Double)] = processedDF
        .select($"domain", $"country", $"crawl_hour", 
                $"url", $"record_size_bytes", $"reputation_score")
        .rdd
        .map(row => ((row.getAs[String]("domain"), 
                      row.getAs[String]("country"), 
                      row.getAs[String]("crawl_hour")), 
                     (1, row.getAs[Long]("record_size_bytes").toDouble, 
                      Option(row.getAs[Double]("reputation_score")).getOrElse(0.0))))
        .reduceByKey((a, b) => (a._1 + b._1, a._2 + b._2, a._3 + b._3))
        .map { case ((domain, country, hour), (count, size, reputation)) => 
          (domain, country, hour, count, size/count, reputation/count) 
        }
      
      // Convert RDD back to DataFrame
      val contentTrendsDF = contentTrendsRDD.toDF(
        "domain", "country", "crawl_hour", "hourly_pages", "avg_page_size", "avg_reputation"
      )
      
      // Time window operations (using DataFrame API for window functions)
      val timeWindow = Window
        .partitionBy($"domain", $"country")
        .orderBy($"crawl_hour")
        .rowsBetween(-6, 0)
      
      val finalTrendsDF = contentTrendsDF
        .withColumn("moving_avg_pages", avg($"hourly_pages").over(timeWindow))
        .orderBy($"domain", $"country", $"crawl_hour")
      
      // Optimize domain popularity calculation with custom partitioning
      val domainPopularityRDD = processedDF
        .select($"domain", $"country", $"url", $"registered_domain", 
                $"url_host_name", $"reputation_score", $"record_size_bytes")
        .rdd
        .map(row => ((row.getAs[String]("domain"), row.getAs[String]("country")), 
                     (row.getAs[String]("url"), 
                      row.getAs[String]("registered_domain"), 
                      row.getAs[String]("url_host_name"),
                      Option(row.getAs[Double]("reputation_score")).getOrElse(0.0),
                      row.getAs[Long]("record_size_bytes"))))
        .partitionBy(new HashPartitioner(200))
      
      // Use mapPartitions for efficient aggregation
      val aggregatedPopularityRDD = domainPopularityRDD
        .mapPartitions { partition =>
          partition.map { case ((domain, country), (url, regDomain, hostName, reputation, size)) =>
            ((domain, country), (1, Set(regDomain), Set(hostName), reputation, size))
          }.reduceByKey { case (a, b) =>
            (a._1 + b._1, a._2 ++ b._2, a._3 ++ b._3, a._4 + b._4, a._5 + b._5)
          }
        }
        .reduceByKey { case (a, b) =>
          (a._1 + b._1, a._2 ++ b._2, a._3 ++ b._3, a._4 + b._4, a._5 + b._5)
        }
        .map { case ((domain, country), (count, domains, hosts, reputation, size)) =>
          (domain, country, count, domains.size, hosts.size, reputation/count, size)
        }
      
      val domainPopularityDF = aggregatedPopularityRDD.toDF(
        "domain", "country", "total_pages", "unique_domains", "unique_hosts", 
        "avg_reputation", "total_content_size"
      ).orderBy($"country", $"total_pages".desc)
      
      // Use SQL for content size distribution (better query optimization)
      processedDF.createOrReplaceTempView("processed_data")
      
      val sizeDistributionDF = spark.sql("""
        SELECT 
          domain, 
          content_category, 
          CASE 
            WHEN record_size_bytes < 10000 THEN 'tiny'
            WHEN record_size_bytes < 100000 THEN 'small'
            WHEN record_size_bytes < 1000000 THEN 'medium'
            ELSE 'large'
          END as size_bucket,
          COUNT(url) as page_count,
          AVG(record_size_bytes) as avg_size,
          MIN(record_size_bytes) as min_size,
          MAX(record_size_bytes) as max_size
        FROM processed_data
        GROUP BY domain, content_category, size_bucket
      """)
      
      // Language distribution with explode optimization and caching
      val languageTempDF = processedDF
        .filter($"content_languages".isNotNull)
        .select($"domain", $"url", $"record_size_bytes",
                explode(split($"content_languages", ",")).as("language"))
        .persist(StorageLevel.MEMORY_AND_DISK)
      
      val languageDistributionDF = languageTempDF
        .groupBy($"domain", $"language")
        .agg(
          count($"url").as("page_count"),
          avg($"record_size_bytes").as("avg_size")
        )
        .orderBy($"domain", $"page_count".desc)
      
      // Write results using optimized partitioning
      val destPath = "s3a://your-bucket/web-analytics-results/"
      
      // Write content trends with appropriate partitioning and compression
      finalTrendsDF
        .repartition(200, $"domain", $"country")
        .write
        .option("compression", "snappy")
        .format("parquet")
        .mode(SaveMode.Overwrite)
        .partitionBy("domain", "country")
        .save(destPath + "content-trends")
      
      domainPopularityDF
        .repartition(100, $"country")
        .write
        .option("compression", "snappy")
        .format("parquet")
        .mode(SaveMode.Overwrite)
        .partitionBy("country")
        .save(destPath + "domain-popularity")
      
      sizeDistributionDF
        .repartition(150, $"domain", $"content_category")
        .write
        .option("compression", "snappy")
        .format("parquet")
        .mode(SaveMode.Overwrite)
        .partitionBy("domain", "content_category")
        .save(destPath + "size-distribution")
      
      languageDistributionDF
        .repartition(100, $"domain")
        .write
        .option("compression", "snappy")
        .format("parquet")
        .mode(SaveMode.Overwrite)
        .partitionBy("domain")
        .save(destPath + "language-distribution")
      
      // Unpersist cached DataFrames
      cachedWarcDF.unpersist()
      optimizedGeoDF.unpersist()
      processedDF.unpersist()
      languageTempDF.unpersist()
      
      println("Optimized implementation completed")
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      println("Invalid Arguments: you must pass <awsAccessKey> <awsSecretKey> <implementationType> arguments")
      println("implementationType should be 'naive' or 'optimized'")
      sys.exit(1)
    }
    
    val awsAccessKey = args(0)
    val awsSecretKey = args(1)
    val implementationType = args(2)
    
    val spark = getSparkSession(awsAccessKey, awsSecretKey)
    
    implementationType.toLowerCase match {
      case "naive" => Naive.processData(spark, awsAccessKey, awsSecretKey)
      case "optimized" => Optimized.processData(spark, awsAccessKey, awsSecretKey)
      case _ => 
        println("Invalid implementation type. Use 'naive' or 'optimized'")
        sys.exit(1)
    }
    
    spark.stop()
  }
}
