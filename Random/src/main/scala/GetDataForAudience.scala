package main.scala
import org.apache.spark.sql.{SparkSession, Row, SaveMode}
import org.apache.spark.sql.functions._
import org.joda.time.{Days, DateTime}
import org.joda.time.format.DateTimeFormat
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.{SaveMode, DataFrame}
import org.apache.spark.ml.attribute.Attribute
import org.apache.spark.ml.feature.{IndexToString, StringIndexer}
//import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Encoders, SparkSession}
import org.joda.time.Days
import org.joda.time.DateTime
import org.apache.hadoop.conf.Configuration
import org.apache.spark.ml.classification.{
  RandomForestClassificationModel,
  RandomForestClassifier
}
import org.apache.spark.ml.classification.MultilayerPerceptronClassifier
import org.apache.spark.ml.classification.{
  GBTClassificationModel,
  GBTClassifier
}

/**
  * The idea of this script is to run random stuff. Most of the times, the idea is
  * to run quick fixes, or tests.
  */
object GetDataForAudience {

  /**
    * This method returns a DataFrame with the data from the audiences data pipeline, for the interval
    * of days specified. Basically, this method loads the given path as a base path, then it
    * also loads the every DataFrame for the days specified, and merges them as a single
    * DataFrame that will be returned.
    *
    * @param spark: Spark Session that will be used to load the data from HDFS.
    * @param nDays: number of days that will be read.
    * @param since: number of days ago from where the data is going to be read.
    *
    * @return a DataFrame with the information coming from the data read.
  **/
  def getDataAudiences(
      spark: SparkSession,
      nDays: Int = 30,
      since: Int = 1
  ): DataFrame = {
    // First we obtain the configuration to be allowed to watch if a file exists or not
    val conf = spark.sparkContext.hadoopConfiguration
    val fs = FileSystem.get(conf)

    // Get the days to be loaded
    val format = "yyyyMMdd"
    val end = DateTime.now.minusDays(since)
    val days = (0 until nDays).map(end.minusDays(_)).map(_.toString(format))
    val path = "/datascience/data_audiences_streaming/"

    // Now we obtain the list of hdfs folders to be read
    val hdfs_files = days
      .map(day => path + "/hour=%s*".format(day))
    // .filter(path => fs.exists(new org.apache.hadoop.fs.Path(path)))
    val df = spark.read.option("basePath", path).parquet(hdfs_files: _*)
    fs.close()

    df
  }

  def getDataTriplets(
      spark: SparkSession,
      country: String,
      nDays: Int = -1,
      path: String = "/datascience/data_triplets/segments/"
  ) = {
    // First we obtain the configuration to be allowed to watch if a file exists or not
    val conf = spark.sparkContext.hadoopConfiguration
    val fs = FileSystem.get(conf)

    val df = if (nDays > 0) {
      // read files from dates
      val format = "yyyyMMdd"
      val endDate = DateTime.now.minusDays(1)
      val days =
        (0 until nDays.toInt).map(endDate.minusDays(_)).map(_.toString(format))
      // Now we obtain the list of hdfs folders to be read
      val hdfs_files = days
        .map(day => path + "/day=%s/country=%s".format(day, country))
        .filter(path => fs.exists(new org.apache.hadoop.fs.Path(path)))
      spark.read.option("basePath", path).parquet(hdfs_files: _*)
    } else {
      // read all date files
      spark.read.load(path + "/day=*/country=%s/".format(country))
    }
    df
  }

  def getDataKeywords(
      spark: SparkSession,
      nDays: Int,
      since: Int
  ): DataFrame = {
    val end = DateTime.now.minusDays(since)

    val lista_files = (0 until nDays)
      .map(end.minusDays(_))
      .map(
        day =>
          "/datascience/data_keywords/day=%s"
            .format(day.toString("yyyyMMdd"))
      )

    val keywords = spark.read
      .format("parquet")
      .option("basePath", "/datascience/data_keywords/")
      .load(lista_files: _*)

    keywords
  }

  /**
    *
    *
    *
    *
    *        DATA DE ENCUESTAS DE LAS VOTACIONES
    *
    *
    *
    *
    */
  def getDataVotaciones(spark: SparkSession) = {
    val data_audience = getDataKeywords(spark, 40, 2).filter("country = 'AR'")
    // getDataAudiences(spark, nDays = 10, since = 1)
    //   .filter(
    //     "country = 'AR' and event_type IN ('tk', 'batch', 'data', 'pv')"
    //   )
    //   .select("device_id", "url", "time") //, "all_segments")
    val data_votaciones =
      spark.read
        .format("csv")
        .option("sep", ",")
        // .option("header", "true")
        .load("/datascience/custom/votacion_2019_impacted.csv")
        .withColumnRenamed("_c0", "device_id")
        .distinct()

    val joint = data_audience
      .join(data_votaciones, Seq("device_id"))
    // .withColumn("all_segments", concat_ws(",", col("all_segments")))

    joint.write
      .format("csv")
      .mode(SaveMode.Overwrite)
      .save("/datascience/custom/votaciones_2019_segments")
  }

  /**
    *
    *
    *            AMEX TIMESTAMPS AND URLS
    *
    *
    */
  def getDataAmexURL(spark: SparkSession) = {
    val data_audience =
      getDataAudiences(spark, nDays = 10, since = 1)
        .filter(
          "country = 'MX' and event_type IN ('tk', 'batch', 'data', 'pv')"
        )
        .select("device_id", "url", "time") //, "all_segments")
    val data_amex =
      spark.read
        .format("csv")
        .option("sep", "\t")
        .load("/datascience/custom/approvable_pgp_employed.csv")
        .withColumnRenamed("_c0", "device_id")
        .repartition(20)
    // .withColumnRenamed("_c1", "cluster")

    val joint = data_audience
      .join(data_amex, Seq("device_id"))
    // .withColumn("all_segments", concat_ws(",", col("all_segments")))

    joint.write
      .format("csv")
      .mode(SaveMode.Overwrite)
      .save("/datascience/custom/amex_con_data_all")
  }

  /**
    *
    *
    *            AMEX SEGMENTS
    *
    *
    */
  def getDataAmexSegments(spark: SparkSession) = {
    val data_segments =
      spark.read.load("/datascience/data_triplets/segments")
    val data_amex =
      spark.read
        .format("csv")
        .option("sep", "\t")
        .load("/datascience/custom/approvable_pgp_employed.csv")
        .withColumnRenamed("_c0", "device_id")
        .repartition(20)
    // .withColumnRenamed("_c1", "cluster")

    val joint = data_segments
      .join(data_amex, Seq("device_id"))
    // .withColumn("all_segments", concat_ws(",", col("all_segments")))

    joint.write
      .format("csv")
      .mode(SaveMode.Overwrite)
      .save("/datascience/custom/amex_con_data_segments")
  }

  /*****************************************************/
  /******************     MAIN     *********************/
  /*****************************************************/
  def main(args: Array[String]) {
    val spark =
      SparkSession.builder
        .appName("Spark devicer")
        .config("spark.sql.files.ignoreCorruptFiles", "true")
        .getOrCreate()

    Logger.getRootLogger.setLevel(Level.WARN)

    getDataVotaciones(spark = spark)

  }
}
