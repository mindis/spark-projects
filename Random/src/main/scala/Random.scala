package main.scala
import org.apache.spark.sql.{SparkSession, Row, SaveMode}
import org.apache.spark.sql.functions.{explode,desc,lit,size,concat,col,concat_ws,collect_list,udf,broadcast,upper,sha2}
import org.joda.time.{Days,DateTime}
import org.apache.spark.sql.functions.{col, count}
import org.apache.spark.sql.SaveMode
import org.apache.spark.ml.attribute.Attribute
import org.apache.spark.ml.feature.{IndexToString, StringIndexer}


/**
 * The idea of this script is to run random stuff. Most of the times, the idea is
 * to run quick fixes, or tests.
 */
object Random {
  def getMadidsFromShareThisWithGEO(spark: SparkSession) {
    val data_us = spark.read.format("csv").option("sep", ",")
                            .load("/datascience/test_us/loading/*.json")
                            .filter("_c2 is not null")
                            .withColumnRenamed("_c0", "d17")
                            .withColumnRenamed("_c5", "city")
                            .select("d17", "city")
    data_us.persist()
    println("LOGGING RANDOM: Total number of records: %s".format(data_us.count()))

    val estid_mapping = spark.read.format("csv")
                                  .option("sep", "\t")
                                  .option("header", "true")
                                  .load("/datascience/matching_estid")

    val joint = data_us.distinct()
                       .join(estid_mapping, Seq("d17"))
                       .select("device_id", "city")

    joint.write
         .mode(SaveMode.Overwrite)
         .save("/datascience/custom/shareThisWithGEO")
  }

  def getCrossDevice(spark: SparkSession) {
    val db_index = spark.read.format("parquet")
                             .load("/datascience/crossdevice/double_index/")
                             .filter("device_type IN ('a', 'i')")
                             .withColumn("index", upper(col("index")))

    val st_data = spark.read.format("parquet")
                            .load("/datascience/custom/shareThisWithGEO")
                            .withColumn("device_id", upper(col("device_id")))
    println("LOGGING RANDOM: Total number of records with GEO and device id: %s".format(st_data.count()))

    val cross_deviced = db_index.join(st_data, db_index.col("index")===st_data.col("device_id")).select("device", "device_type", "city")

    cross_deviced.write
                 .mode(SaveMode.Overwrite)
                 .save("/datascience/custom/madidsShareThisWithGEO")
  }

  def getEstIdsMatching(spark: SparkSession) = {
    val format = "yyyy/MM/dd"
    val start = DateTime.now.minusDays(30)
    val end   = DateTime.now.minusDays(15)

    val daysCount = Days.daysBetween(start, end).getDays()
    val days = (0 until daysCount).map(start.plusDays(_)).map(_.toString(format))

    val dfs = (0 until daysCount).map(start.plusDays(_))
      .map(_.toString(format))
      .map(x => spark.read.format("csv").option("sep", "\t")
                      .option("header", "true")
                      .load("/data/eventqueue/%s/*.tsv.gz".format(x))
                      .filter("d17 is not null and country = 'US' and event_type = 'sync'")
                      .select("d17","device_id")
                      .dropDuplicates())

    val df = dfs.reduce(_ union _)

    df.write.format("csv").option("sep", "\t")
                    .option("header",true)
                    .mode(SaveMode.Overwrite)
                    .save("/datascience/matching_estid_2")
  }

  def process_geo(spark: SparkSession) {
    val path = "/datascience/test_us/loading/"
    val data = spark.read.csv(path).withColumnRenamed("_c0", "estid")
                                  .withColumnRenamed("_c1", "utc_timestamp")
                                  .withColumnRenamed("_c2", "latitude")
                                  .withColumnRenamed("_c3", "longitude")
                                  .withColumnRenamed("_c4", "zipcode")
                                  .withColumnRenamed("_c5", "city")
                                  .withColumn("day", lit(DateTime.now.toString("yyyyMMdd")))
    data.write.format("parquet")
              .mode("append")
              .partitionBy("day")
              .save("/datascience/geo/US/")
  }

  def generate_index(spark:SparkSession){
    val df = spark.read.format("parquet").load("/datascience/data_demo/triplets_segments").limit(50000)
    val indexer1 = new StringIndexer().setInputCol("device_id").setOutputCol("deviceIndex")
    val indexed1 = indexer1.fit(df).transform(df)
    val indexer2 = new StringIndexer().setInputCol("feature").setOutputCol("featureIndex")
    val indexed2 = indexer2.fit(indexed1).transform(indexed1)

    val tempDF = indexed2.withColumn("deviceIndex",indexed2("deviceIndex").cast("long"))
                        .withColumn("featureIndex",indexed2("featureIndex").cast("long"))
                        .withColumn("count",indexed2("count").cast("double"))
                        
    tempDF.write.format("parquet")
                 .mode(SaveMode.Overwrite)
                 .save("/datascience/data_demo/triplets_segments_indexed")  
  }
  
  def generate_data_leo(spark:SparkSession){
    val xd_users = spark.read.format("parquet")
                        .load("/datascience/crossdevice/double_index")
                        .filter("device_type = 'c'")
                        .limit(2000)

    val xd = spark.read.format("csv").option("sep","\t")
                  .load("/datascience/audiences/crossdeviced/taxo")
                  .withColumnRenamed("_c0","index")
                  .withColumnRenamed("_c1","device_type")
                  .withColumnRenamed("_c2","segments_xd")
                  .drop("device_type")

    val joint = xd.join(broadcast(xd_users),Seq("index")) 
    joint.select("segments_xd","android","ios").write.format("csv").option("sep","\t").save("/datascience/data_leo")
  
  }

  def main(args: Array[String]) {
    val spark = SparkSession.builder.appName("Run matching estid-device_id").getOrCreate()
    generate_data_leo(spark)
  }
}