//> using file ../project.scala

import java.time.{Instant,ZoneId}
import java.time.format.DateTimeFormatter
import com.mchange.sysadmin.taskrunner.*
import math.Ordering.Implicits.infixOrderingOps

object Zipper:
  case class Pad( lastZipTime : Option[Instant] = None, lastUpdateTime : Option[Instant] = None, tmpDir : Option[os.Path] = None )
  val TimestampFormatterRaw = DateTimeFormatter.ofPattern("'--'yyyy'-'MM'-'dd'--'HH'h'mm'm'ss's'")
  val TimestampedRegex = """^(.+)(\-\-\d{4}\-\d{2}\-\d{2}\-\-\d{2}h\d{2}m\d{2}s)\.(.+)$""".r
class Zipper( baseName : String, siteDir : os.Path, zipDir : os.Path, zoneId : ZoneId = ZoneId.systemDefault() ):
  import Zipper.*

  val TimestampFormatter = TimestampFormatterRaw.withZone( zoneId )

  def conditionallyZip =
    val taskRunner = TaskRunner[Pad]
    import taskRunner.*

    // sequential
    val ComputeLastZipTime =
      Step.Arbitrary("Compute last zip time"): ( prior, thisStep ) =>
        if os.exists( zipDir ) then
          val timestamps =
            os.list( zipDir )
              .map( _.lastOpt )
              .collect {
                case Some( TimestampedRegex(bn,timestamp,suffix)) if bn == baseName && suffix == "zip" => Instant.from(TimestampFormatter.parse(timestamp))
              }
          if timestamps.nonEmpty then
            val last = timestamps.max
            Result.onward(prior.copy( lastZipTime = Some(last) ), notes = Some(s"Latest timestamp found in zip directory: ${last}"))
          else
            Result.onward(prior.copy( lastZipTime = Some(Instant.MIN) ), notes = Some("No timestamps found in zip directory"))
        else
          Result.onward(prior.copy( lastZipTime = Some(Instant.MIN) ), notes = Some("No zip directory, so no prior zips found."))

    val ComputeLastUpdateTime =
      Step.Arbitrary("Compute last update time"): ( prior, thisStep ) =>
        if os.exists( siteDir ) then
          val mtimes = os.walk.attrs( siteDir ).map( _(1).mtime.toInstant )
          if mtimes.nonEmpty then 
            Result.onward(prior.copy( lastUpdateTime = Some(mtimes.max) ), notes = Some("Latest update found in site directory"))
          else
            Result(None,"",s"Site dir '${siteDir}' is empty.", prior, Some(s"Site dir '${siteDir}' is empty, won't zip."))
        else
          Result(None,"",s"Site dir '${siteDir}' does not exist.", prior, Some(s"Site dir '${siteDir}' does not exist, won't zip."))

    val CreateTmpDirIfZipping =
      Step.Arbitrary("Create tmp directory if zipping"): ( prior, thisStep ) =>
        val mbShouldZip =
          for
            lastZip    <- prior.lastZipTime
            lastUpdate <- prior.lastUpdateTime
          yield
            lastUpdate > lastZip
        val shouldZip = mbShouldZip.getOrElse(false)
        if shouldZip then
          val tmpDir = os.temp.dir()
          Result.onward(prior.copy( tmpDir = Some(tmpDir) ), notes = Some(s"We're gonna zip, made temporary directory: ${tmpDir}"))
        else
          Result.onward(prior, notes = Some("We're not zipping."))

    val Zip =
      Step.Arbitrary("Zip site directory"): ( prior, thisStep ) =>
        if prior.tmpDir.nonEmpty then // we should zip!
          val fname = baseName + TimestampFormatter.format(Instant.now()) + ".zip"
          val zipfile = prior.tmpDir.get / fname
          os.zip( dest = zipfile, sources = Seq( Tuple2(siteDir,os.SubPath(baseName)) ), preserveMtimes = true, followLinks = false )
          Result.onward(prior, notes = Some( s"Zipping ${siteDir} to $zipfile as $baseName." ))
        else // no failures have occurred, but we should not zip  
          Result.onward(prior, notes = Some( s"${siteDir} is unchanged since last zip, not zipping." ))


    val Copy =
      Step.Arbitrary("Copy new zip into zip directory"): ( prior, thisStep ) =>
        if prior.tmpDir.nonEmpty then // we should copy!
          val tmpDir = prior.tmpDir.get
          if !os.exists( zipDir ) then
            os.makeDir.all( zipDir )
          os.copy( tmpDir, zipDir, mergeFolders = true )
          Result.onward( prior, notes = Some(s"""Copying ${os.list(tmpDir).mkString(", ")} into ${zipDir}."""))
        else // no failures have occurred, but we should not zip  
          Result.onward(prior, notes = Some( s"No new zip files need to be copied into ${zipDir}." ))

    // clean ups
    val DeleteTmpDir =
      Step.Arbitrary("Delete tmp directory"): ( prior, thisStep ) =>
        if prior.tmpDir.nonEmpty then
          val tmpDir = prior.tmpDir.get
          os.remove.all( tmpDir )
          Result.onward( prior, notes=Some(s"Removing ${tmpDir}") )
        else  
          Result.onward( prior, notes=Some(s"No tmp directory was created, nothing to remove.") )

    val task = new Task:
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
    runAndReport(task, reporters)
    println(s"Zip of ${baseName} and reporting of results complete.")






