//> using file ../project.scala

import java.time.Instant
import java.time.format.DateTimeFormatter
import com.mchange.sysadmin.taskrunner.*
import math.Ordering.Implicits.infixOrderingOps


object Zipper:
  case class Pad( lastZipTime : Option[Instant] = None, lastUpdateTime : Option[Instant] = None, tmpDir : Option[os.Path] = None )
  val TimestampFormatter = DateTimeFormatter.ofPattern("'-'yyyy'-'MM'-'dd'-'HH'h'mm'm'ss's'")
  val TimestampedRegex = """^(.+)(\-\d{4}\-\d{2}\-\d{2}\-\d{2}h\d{2}\m\d{2}s)\.(.+)$""".r
class Zipper( baseName : String, siteDir : os.Path, zipDir : os.Path ):
  import Zipper.*

  def conditionallyZip =
    val tr = TaskRunner[Pad]

    // sequential
    val ComputeLastZipTime =
      def action( prior : Pad, thisStep : tr.Arbitrary ) : tr.Result =
        if os.exists( zipDir ) then
          val timestamps =
            os.list( zipDir )
              .map( _.lastOpt )
              .collect {
                case Some( TimestampedRegex(bn,timestamp,suffix)) if bn == baseName && suffix == "zip" => Instant.from(TimestampFormatter.parse(timestamp))
              }
          if timestamps.nonEmpty then
            val last = timestamps.max
            tr.Result(None,"","",prior.copy( lastZipTime = Some(last) ), Some(s"Latest timestamp found in zip directory: ${last}"))
          else
            tr.Result(None,"","",prior.copy( lastZipTime = Some(Instant.MIN) ), Some("No timestamps found in zip directory"))
        else
          tr.Result(None,"","",prior.copy( lastZipTime = Some(Instant.MIN) ), Some("No zip directory, so no prior zips found."))
      tr.arbitrary("Compute last zip time", action )

    val ComputeLastUpdateTime =
      def action( prior : Pad, thisStep : tr.Arbitrary ) : tr.Result =
        if os.exists( siteDir ) then
          val mtimes = os.walk.attrs( siteDir ).map( _(1).mtime.toInstant )
          if mtimes.nonEmpty then 
            tr.Result(None,"","",prior.copy( lastUpdateTime = Some(mtimes.max) ), Some("Latest update found in site directory"))
          else
            tr.Result(None,"",s"Site dir '${siteDir}' is empty.", prior, Some(s"Site dir '${siteDir}' is empty, won't zip."))
        else
          tr.Result(None,"",s"Site dir '${siteDir}' does not exist.", prior, Some(s"Site dir '${siteDir}' does not exist, won't zip."))
      tr.arbitrary("Compute last update time", action )

    val CreateTmpDirIfZipping =
      def action( prior : Pad, thisStep : tr.Arbitrary ) : tr.Result =
        val mbShouldZip =
          for
            lastZip    <- prior.lastZipTime
            lastUpdate <- prior.lastUpdateTime
          yield
            lastUpdate > lastZip
        val shouldZip = mbShouldZip.getOrElse(false)
        if shouldZip then
          val tmpDir = os.temp.dir()
          tr.Result(None,"","",prior.copy( tmpDir = Some(tmpDir) ), Some(s"We're gonna zip, made temporary directory: ${tmpDir}"))
        else
          tr.Result(None,"",s"Not zipping: ${pprint(prior).plainText}",prior, Some(s"We're not zipping: ${pprint(prior).plainText}"))
      tr.arbitrary("Create tmp directory if zipping", action )

    val Zip =
      //tr.exec("Zipping site directory", List("zip", (prior.tmpDir.get / (baseName + TimestampFormatter.format(Instant.now()) + ".zip")).toString, prior.siteDir.get )
      def action( prior : Pad, thisStep : tr.Arbitrary ) : tr.Result =
        val fname = baseName + TimestampFormatter.format(Instant.now()) + ".zip"
        val zipfile = prior.tmpDir.get / fname
        os.zip( dest = zipfile, sources = Seq( siteDir ), followLinks = false )
        tr.Result(None,"","",prior, Some( s"Zipping ${siteDir} to $zipfile." ))
      tr.arbitrary("Zip site directory", action )


    val Copy =
      def action( prior : Pad, thisStep : tr.Arbitrary ) : tr.Result =
        val tmpDir = prior.tmpDir.get
        os.copy( tmpDir, zipDir, mergeFolders = true )
        tr.result( None,"","",prior,Some(s"""Copying ${os.list(tmpDir).mkString(", ")} into zip dir."""))
      tr.arbitrary("Copy new zip into zip directory", action )

    // clean ups
    val DeleteTmpDir =
      def action( prior : Pad, thisStep : tr.Arbitrary ) : tr.Result =
        val tmpDir = prior.tmpDir.get
        os.remove.all( tmpDir )
        tr.result( None,"","",prior,Some(s"Removing ${tmpDir}") )
      tr.arbitrary("Delete tmp directory", action )

    val task = new tr.Task:
      val name = s"Zip site '${baseName}'"
      val init = Pad()
      val bestEffortSetups = Set.empty
      val sequential = List(
        ComputeLastZipTime,
        ComputeLastUpdateTime,
        CreateTmpDirIfZipping,
        Zip,
        Copy
      )
      val bestEffortFollowups = Set(
        DeleteTmpDir
      )
    end task

    val reporters = Reporters.default()
    tr.runAndReport(task, reporters)
    println(s"Zip of ${baseName} and reporting of results complete.")






