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

//import org.apache.spark.mllib.feature.Stemmer

import java.security.MessageDigest
import java.util
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import java.time.DateTimeException
import java.sql.Savepoint

/**
  * The idea of this script is to run random stuff. Most of the times, the idea is
  * to run quick fixes, or tests.
  */
object Random {
 
  /**
    *
    *
    *
    *                PITCH DATA_KEYWORDS
    *
    *
    *
    */

  // es _days porque podría ser por hora (hacer un if para seleccionar esto sino)
  def read_data_kw_days(
      spark: SparkSession,
      nDays: Integer,
      since: Integer) : DataFrame = {

    val conf = spark.sparkContext.hadoopConfiguration
    val fs = FileSystem.get(conf)

    // Get the days to be loaded
    val format = "yyyyMMdd"
    val end = DateTime.now.minusDays(since)
    val days = (0 until nDays).map(end.minusDays(_)).map(_.toString(format))
    val path = "/datascience/data_keywords"

    // Now we obtain the list of hdfs folders to be read
    val hdfs_files = days
      .map(day => path + "/day=%s/country=AR".format(day)) //para cada dia de la lista day devuelve el path del día
      .filter(file_path => fs.exists(new org.apache.hadoop.fs.Path(file_path))) //es como if os.exists

    val df = spark.read
      .option("basePath", path).parquet(hdfs_files: _*)
      .select("content_keys","device_id")
      .na.drop() //lee todo de una

    df
  }


  def get_joint_keys(
      df_keys: DataFrame,
      df_data_keywords: DataFrame) : DataFrame = {

    val df_joint = df_data_keywords.join(broadcast(df_keys), Seq("content_keys"))
    df_joint
      .select("content_keys","device_id")
      .dropDuplicates()
      .groupBy("device_id")
      .agg(collect_list("content_keys").as("kws"))
      //.withColumn("device_type", lit("web")) para empujar
      //.select("device_type", "device_id", "seg_id")
      .select("device_id","kws")
  }
    
  def save_query_results(
      df_queries: DataFrame,
      df_joint: DataFrame,
      job_name: String) = {
  
    df_joint.cache()

    val tuples = df_queries.select("seg_id", "query")
      .collect()
      .map(r => (r(0).toString, r(1).toString))
    for (t <- tuples) {
      df_joint
        .filter(t._2)
        .select("device_id")
        .write
        .format("csv")
        .option("sep", "\t")
        .mode(SaveMode.Overwrite)
        .save("/datascience/devicer/processed/%s_%s".format(job_name,t._1))
    }
  }
  


  /**
  //create df from list of tuples

  // leer values de algun lado

  // Create `Row` from `Seq`
  val row = Row.fromSeq(values)

  // Create `RDD` from `Row`
  val rdd = spark.sparkContext.makeRDD(List(row))

  // Create schema fields
  val fields = List(
    StructField("query", StringType, nullable = false),
    StructField("seg_id", Integerype, nullable = false)
  )

  // Create `DataFrame`
  val dataFrame = spark.createDataFrame(rdd, StructType(fields))

   */


  //main method:
  
  //kw_list: List[String],     pasarle estos params a la funcion para pedidos futuros
  //tuple_list: List[String],

  def get_pitch(
      spark: SparkSession,
      nDays: Integer,
      since: Integer,
      job_name: String) = {
    
    val df_data_keywords = read_data_kw_days(spark = spark,
                                             nDays = nDays,
                                             since = since)
    
    // a get_joint_keys pasarle un df con la columna content_keys,
    // creado a partir de una lista de keywords (levanto la lista de un json)
    //la siguiente linea es temp:  

    val df_keys = spark.read
      .format("csv")
      .option("header", "true")
      .load("/datascience/custom/content_keys_danone.csv")
    
    val df_joint = get_joint_keys(df_keys = df_keys,
                                  df_data_keywords = df_data_keywords)

    //pasarle una lista de tuplas del tipo (query,ID)
    //la siguiente linea es temp:
    
    val df_queries = spark.read
      .format("csv")
      .option("header", "true")
      .load("/datascience/custom/queries_danone.csv")


    save_query_results(df_queries = df_queries,
                       df_joint = df_joint,
                       job_name = job_name)

  }
  

  
  /**
    *
    *
    *
    *
    *
    *
    *                    TEST STEMMING
    *
    *
    *
    *
    *
    */
  //country se deberá leer del json
  def test_no_stemming(spark: SparkSession
                       nDays: Integer,
                       since: Integer) = {    

    val conf = spark.sparkContext.hadoopConfiguration
    val fs = FileSystem.get(conf)

    // Get the days to be loaded
    val format = "yyyyMMdd"
    val end = DateTime.now.minusDays(since)
    val days = (0 until nDays).map(end.minusDays(_)).map(_.toString(format)) //lista con formato de format
    val path = "/datascience/data_keywords"

    //country se deberá leer del json
    val country = "AR"
    // Now we obtain the list of hdfs folders to be read
    val hdfs_files = days
      .map(day => path + "/day=%s/country=%s".format(day,country)) //para cada dia de la lista day devuelve el path del día
      .filter(file_path => fs.exists(new org.apache.hadoop.fs.Path(file_path))) //es como if os.exists

    val df = spark.read.option("basePath", path).parquet(hdfs_files: _*) //lee todo de una
    val content_kws = spark.read
      .format("csv")
      .option("header", "true")
      .load("/datascience/custom/content_keys_danone.csv")
    val joint = df.join(broadcast(content_kws), Seq("content_keys"))
    joint.write
      .format("csv")
      .option("header", "true")
      .mode(SaveMode.Overwrite)
      .save("/datascience/custom/test_joint_keys_no_stemming")
  }


  def test_stemming(spark: SparkSession,
                    nDays: Integer,
                    since: Integer) = {          

    val conf = spark.sparkContext.hadoopConfiguration
    val fs = FileSystem.get(conf)

    val toArray = udf[Array[String], String]( _.split(" "))

    // Get the days to be loaded
    val format = "yyyyMMdd"
    val end = DateTime.now.minusDays(since)
    val days = (0 until nDays).map(end.minusDays(_)).map(_.toString(format)) //lista con formato de format
    val path = "/datascience/data_keywords"

    //country se deberá leer del json
    val country = "AR"
    // Now we obtain the list of hdfs folders to be read
    val hdfs_files = days
      .map(day => path + "/day=%s/country=%s".format(day,country)) //para cada dia de la lista day devuelve el path del día
      .filter(file_path => fs.exists(new org.apache.hadoop.fs.Path(file_path))) //es como if os.exists

    val df = spark.read
      .option("basePath", path).parquet(hdfs_files: _*) //lee todo de una
      .withColumn("content_keys", toArray(df("content_keys")))

    df = new Stemmer()
      .setInputCol("content_keys")
      .setOutputCol("stemmed")
      .setLanguage("Spanish")
      .transform(df)
      .withColumn("stemmed" ,concat_ws(" ", col("stemmed")))
      .withColumn("content_keys" ,concat_ws(" ", col("content_keys")))

    val content_kws = spark.read
      .format("csv")
      .option("header", "true")
      .load("/datascience/custom/content_keys_danone.csv")
      .withColumn("content_keys", toArray(content_kws("content_keys")))

    content_keys_UY = new Stemmer()
      .setInputCol("content_keys")
      .setOutputCol("stemmed")
      .setLanguage("Spanish")
      .transform(content_keys_UY)
      .withColumn("stemmed" ,concat_ws(" ", col("stemmed")))
      .dropDuplicates("stemmed").select("seg_id","stemmed")

    val joint = df.join(broadcast(content_keys_UY), Seq("stemmed"))
    joint.write
      .format("csv")
      .option("header", "true")
      .mode(SaveMode.Overwrite)
      .save("/datascience/custom/test_joint_keys_stemmed")
  }
    


  /*****************************************************/
  /******************     MAIN     *********************/
  /*****************************************************/
  def main(args: Array[String]) {
    val spark =
      SparkSession.builder.appName("Spark devicer").config("spark.sql.files.ignoreCorruptFiles", "true").getOrCreate()

    Logger.getRootLogger.setLevel(Level.WARN)
    
    get_pitch(spark = spark, 3 , 1, "pitch_danone") 
     
  }

}