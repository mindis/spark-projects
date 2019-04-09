package main.scala
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{
  upper,
  col,
  abs,
  udf,
  regexp_replace,
  split,
  lit,
  explode,
  length,
  to_timestamp,
  from_unixtime,
  date_format,
  sum
}
import org.apache.spark.sql.SaveMode
import org.joda.time.Days
import org.joda.time.DateTime
import org.apache.spark.sql.functions.broadcast
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.{SaveMode, DataFrame}

object TrainingSetAR {

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
    val path = "/datascience/data_audiences"

    // Now we obtain the list of hdfs folders to be read
    val hdfs_files = days
      .map(day => path + "/day=%s".format(day))
      .filter(path => fs.exists(new org.apache.hadoop.fs.Path(path)))
    val df = spark.read.option("basePath", path).parquet(hdfs_files: _*)

    df
  }

  /**
    * This method takes all the triplets with all the segments, for the users with ground truth in AR.
    */
  def generateSegmentTriplets(spark: SparkSession) = {
    val segments =
      """26,32,36,59,61,82,85,92,104,118,129,131,141,144,145,147,149,150,152,154,155,158,160,165,166,177,178,210,213,218,224,225,226,230,245,
      247,250,264,265,270,275,276,302,305,311,313,314,315,316,317,318,322,323,325,326,352,353,354,356,357,358,359,363,366,367,374,377,378,379,380,384,385,
      386,389,395,396,397,398,399,401,402,403,404,405,409,410,411,412,413,418,420,421,422,429,430,432,433,434,440,441,446,447,450,451,453,454,456,457,458,
      459,460,462,463,464,465,467,895,898,899,909,912,914,915,916,917,919,920,922,923,928,929,930,931,932,933,934,935,937,938,939,940,942,947,948,949,950,
      951,952,953,955,956,957,1005,1116,1159,1160,1166,2064,2623,2635,2636,2660,2719,2720,2721,2722,2723,2724,2725,2726,2727,2733,2734,2735,2736,2737,2743,
      3010,3011,3012,3013,3014,3015,3016,3017,3018,3019,3020,3021,3022,3023,3024,3025,3026,3027,3028,3029,3030,3031,3032,3033,3034,3035,3036,3037,3038,3039,
      3040,3041,3042,3043,3044,3045,3046,3047,3048,3049,3050,3051,3055,3076,3077,3084,3085,3086,3087,3302,3303,3308,3309,3310,3388,3389,3418,3420,3421,3422,
      3423,3450,3470,3472,3473,3564,3565,3566,3567,3568,3569,3570,3571,3572,3573,3574,3575,3576,3577,3578,3579,3580,3581,3582,3583,3584,3585,3586,3587,3588,
      3589,3590,3591,3592,3593,3594,3595,3596,3597,3598,3599,3600,3730,3731,3732,3733,3779,3782,3843,3844,3913,3914,3915,4097,
      5025,5310,5311""".replace("\n", "").split(",").toList.toSeq

    val gt = spark.read
      .format("csv")
      .option("sep", "\t")
      .load(
        "/datascience/devicer/processed//datascience/devicer/to_process/AR_xd-0_partner-_pipe-0_2019-04-09T18-18-41-066436_grouped/*"
      )
      .withColumnRenamed("_c1", "device_id")
      .withColumnRenamed("_c2", "label")
      .select("device_id", "label")

    val triplets =
      spark.read
        .load("/datascience/data_demo/triplets_segments/country=AR/")
        .filter(col("feature").isin(segments: _*))

    triplets
      .join(gt, Seq("device_id"))
      .write
      .format("csv")
      .mode(SaveMode.Overwrite)
      .save("/datascience/data_demo/triplets_dataset_ar")
  }

  /**
   * This method calculates all the datasets for the AR ground truth users, based only on Google Analytics (GA) data.
  */
  def getGARelatedData(spark: SparkSession) {
    // First we load the GA data
    val ga = spark.read
      .load(
        "/datascience/data_demo/join_google_analytics/country=AR/"
      )
      .dropDuplicates("url", "device_id")

    // Now we load the ground truth users
    val users = spark.read
      .format("csv")
      .option("sep", "\t")
      .load("/datascience/devicer/processed/AR_xd-0_partner-_pipe-0_2019-04-09T18-18-41-066436_grouped/*")
      .withColumnRenamed("_c1", "device_id")
      .withColumnRenamed("_c2", "label")
      .select("device_id", "label")

    // Here I calculate the data of GA just for 
    val joint = ga.join(users, Seq("device_id"))
      .write
      .format("csv")
      .mode(SaveMode.Overwrite)
      .save("/datascience/data_demo/ga_dataset_AR_training")

    joint.cache()

    // Finally we obtain the data the is related to timestamps coming from GA
    val myUDF = udf(
      (weekday: String, hour: String) =>
        if (weekday == "Sunday" || weekday == "Saturday") "%s1".format(hour)
        else "%s0".format(hour)
    )
    joint
      .withColumn("Time", to_timestamp(from_unixtime(col("timestamp"))))
      .withColumn("Hour", date_format(col("Time"), "HH"))
      .withColumn("Weekday", date_format(col("Time"), "EEEE"))
      .withColumn("wd", myUDF(col("Weekday"), col("Hour")))
      .groupBy("device_id", "wd")
      .count()
      .groupBy("device_id")
      .pivot("wd")
      .agg(sum("count"))
      .write
      .format("csv")
      .option("header", "true")
      .mode(SaveMode.Overwrite)
      .save("/datascience/data_demo/ga_timestamp_AR_training")
  }

  def getDatasetFromURLs(spar: SparkSession) = {
    // Now we load the ground truth users
    val users = spark.read
      .format("csv")
      .option("sep", "\t")
      .load("/datascience/devicer/processed/AR_xd-0_partner-_pipe-0_2019-04-09T18-18-41-066436_grouped/*")
      .withColumnRenamed("_c1", "device_id")
      .withColumnRenamed("_c2", "label")
      .select("device_id", "label")

    // Data from data audiences
    val df = getDataAudiences(spark)
      .filter("country = 'AR' AND event_type IN ('pv', 'batch')")
      .select("device_id", "url")
    
    // Here we store the data
    df.join(users, Seq("device_id"))
      .distinct()
      .groupBy("device_id", "url")
      .count()
      .write
      .mode(SaveMode.Overwrite)
      .format("csv")
      .option("sep", "\t")
      .save("/datascience/custom/urls_gt_ar")
  }

  def main(args: Array[String]) {
    val spark = SparkSession.builder
      .appName("Get triplets: keywords and segments")
      .getOrCreate()

    generateSegmentTriplets(spark)
    getGARelatedData(spark)
    getDatasetFromURLs(spark)
  }
}
