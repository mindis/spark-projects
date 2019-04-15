package main.scala

import org.apache.spark.sql.SparkSession
import org.apache.hadoop.fs.{FileSystem, Path}
import org.joda.time.DateTime
import org.apache.spark.sql.functions._
import org.apache.spark.sql.SaveMode

import org.datasyslab.geosparksql.utils.{Adapter, GeoSparkSQLRegistrator}
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory}
import org.datasyslab.geospark.spatialRDD.SpatialRDD
import org.apache.spark.storage.StorageLevel

import org.apache.spark.serializer.KryoRegistrator
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator
import org.datasyslab.geosparkviz.core.Serde.GeoSparkVizKryoRegistrator

object GeoSparkMatcher {

  /**
    * This method reads the safegraph data, selects the columns "ad_id" (device id), "id_type" (user id), "latitude", "longitude", creates a
    * geocode for each row and future spatial operations and finally removes duplicates users that were detected in the same
    * location (i.e. the same user in different lat long coordinates will be conserved, but the same user in same lat long coordinates will be dropped).
    *
    * @param spark: Spark session that will be used to load the data.
    * @param value_dictionary: Map with all the parameters. In particular there are three parameters that has to be contained in the Map: country, since and nDays.
    *
    * @return df_safegraph: dataframe created with the safegraph data, filtered by the desired country, extracting the columns user id, device id, latitude and
    * longitude removing duplicate users that have repeated locations and with added geocode.
    */
  def get_safegraph_data(
      spark: SparkSession,
      value_dictionary: Map[String, String]
  ) = {
    // First we obtain the configuration to be allowed to watch if a file exists or not
    val conf = spark.sparkContext.hadoopConfiguration
    val fs = FileSystem.get(conf)

    // Get the days to be loaded
    val format = "yyyy/MM/dd"
    val end = DateTime.now.minusDays(value_dictionary("since").toInt)
    val days = (0 until value_dictionary("nDays").toInt)
      .map(end.minusDays(_))
      .map(_.toString(format))

    // Now we obtain the list of hdfs files to be read
    val hdfs_files = days
      .map(day => path + "%s/".format(day))
      .filter(
        path => fs.exists(new org.apache.hadoop.fs.Path("/data/geo/safegraph/"))
      )
      .map(day => day + "*.gz")

    // Finally we read, filter by country, rename the columns and return the data
    val df_safegraph = spark.read
      .option("header", "true")
      .csv(hdfs_files: _*)
      .filter("country = '%s'".format(value_dictionary("country")))
      .select("ad_id", "id_type", "latitude", "longitude", "utc_timestamp")

    df_safegraph.createOrReplaceTempView("data")
    var safegraphDf = spark
      .sql("""
          SELECT ST_Transform(ST_Point(CAST(data.longitude AS Decimal(24,20)), 
                                       CAST(data.latitude AS Decimal(24,20)), 
                                       data.ad_id,
                                       data.id_type,
                                       data.utc_timestamp),
                              "epsg:4326", 
                              "epsg:3857") AS pointshape
          FROM data
      """)

    safegraphDf
  }

  /**
    * This method reads the user provided POI dataset, and renames the columns. The file provided must be correctly formatted as described below.
    *
    * @param spark: Spark session that will be used to load the data.
    * @param file_name: path of the dataset containing the POIs. Must be correctly formated as described (name|latitude|longitude (without the index),
    * with the latitude and longitude with point (".") as decimal delimiter.)
    *
    * @param return df_pois_final: dataframe created from the one provided by the user containing the POIS: contains the geocode and renamed columns.
    */
  def get_POI_coordinates(
      spark: SparkSession,
      value_dictionary: Map[String, String]
  ) = {

    // Loading POIs. The format of the file is Name, Latitude, Longitude
    val df_pois = spark.read
      .option("header", "true")
      .option("delimiter", ",")
      .csv(value_dictionary("path_to_pois"))

    val other_columns = df_pois.columns
      .filter(c => c != "latitude" && c != "longitude")
      .mkString(",")
    df_pois.createOrReplaceTempView("POIs")
    var poisDf = spark
      .sql("""
          SELECT ST_Transform(ST_Point(CAST(POIs.longitude AS Decimal(24,20)), 
                                       CAST(POIs.latitude  AS Decimal(24,20)),
                                       %s),
                              "epsg:4326", 
                              "epsg:3857") AS pointshape
          FROM POIs
      """.format(other_columns))
    poisDf
  }

  def join(spark: SparkSession, value_dictionary: Map[String, String]) = {
    val safegraphDf = get_safegraph_data(spark, value_dictionary)
    val poisDf = get_POI_coordinates(spark, value_dictionary)

    // TODO: pasar por parametro las reparticiones
    safegraphDf.repartition(500).createOrReplaceTempView("safegraph")
    poisDf.repartition(10)
    // TODO: pasar por parametro si se quiere o no persistir
    poisDf.persist(StorageLevel.MEMORY_ONLY)
    poisDf.createOrReplaceTempView("poisPoints")

    var distanceJoinDf = spark.sql(
      """select *
      from safegraph, poisPoints
      where ST_Distance(safegraph.pointshape, poisPoints.pointshape) < 100"""
    )

    // TODO: Overwrite output
    distanceJoinDf.rdd
      .map(
        arr =>
          a(0)
            .asInstanceOf[com.vividsolutions.jts.geom.Geometry]
            .getUserData()
            .toString
            .replaceAll("\\s{1,}", ",") + "," +
            a(1)
              .asInstanceOf[com.vividsolutions.jts.geom.Geometry]
              .getUserData()
              .toString
              .replaceAll("\\s{1,}", ",")
      )
      .saveAsTextFile(
        "/datascience/geo/%s".format(value_dictionary("poi_output_file"))
      )
  }

  type OptionMap = Map[Symbol, Any]

  /**
    * This method parses the parameters sent.
    */
  def nextOption(map: OptionMap, list: List[String]): OptionMap = {
    def isSwitch(s: String) = (s(0) == '-')
    list match {
      case Nil => map
      case "--path_geo_json" :: value :: tail =>
        nextOption(map ++ Map('path_geo_json -> value.toString), tail)
    }
  }

  def main(args: Array[String]) {
    // Parse the parameters
    val options = nextOption(Map(), args.toList)
    val path_geo_json =
      if (options.contains('path_geo_json)) options('path_geo_json).toString
      else ""

    // Start Spark Session
    val spark = SparkSession
      .builder()
      .config("spark.serializer", classOf[KryoSerializer].getName)
      .config(
        "spark.kryo.registrator",
        classOf[GeoSparkKryoRegistrator].getName
      )
      // .config("geospark.global.index", "true")
      // .config("geospark.global.indextype", "rtree")
      // .config("geospark.join.gridtype", "kdbtree")
      // .config("geospark.join.numpartition", 200)
      .appName("match_POI_geospark")
      .getOrCreate()

    val value_dictionary = get_variables(spark, path_geo_json)

    join(spark, value_dictionary)
  }
}
