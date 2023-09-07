//> using file project.scala

import scala.collection.*
import scala.util.Try  
import com.mchange.sysadmin.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

abstract class BackupDbRunner:
  def extractHostname( args : Array[String] ) : String
  def extractDestpath( args : Array[String] ) : String
  
  def computeDoBackupParsedCommand( args : Array[String], backupFilePath : os.Path ) : List[String]
  def doBackupActionDescription : String

  def displayDbName    : String
  def dumpFileCoreName : String

  def performDbBackup(args : Array[String]) =

    val hname    = extractHostname(args)
    val destpath = extractDestpath(args).reverse.dropWhile( _ == '/' ).reverse // no trailing slashes!

    val isRcloneDest = destpath.contains(':')
    val backupFileName = s"${hname}-${dumpFileCoreName}-${ISO_LOCAL_DATE.format(LocalDate.now())}"

    val fullDestPath = s"${destpath}/${hname}"

    case class Pad( tmpDir : Option[os.Path] = None, backupFile : Option[os.Path] = None )

    val tr = new TaskRunner[Pad]

    // sequential
    val EnsureRcloneIfNecessary =
      def action( prior : Pad, thisStep : tr.Arbitrary ) =
        val out = if isRcloneDest then
          tr.arbitraryExec( prior, thisStep, List("rclone", "--version"), tr.carryPrior )
        else
          tr.Result.zeroWithCarryForward(prior)
        out.copy( notes = Some(s"destpath: ${destpath}") )
      tr.arbitrary(s"Ensure availability of rclone, if necessary", action ).copy( actionDescription = Some("rclone --version") )

    val CreateTempDir =
      def action( prior : Pad, thisStep : tr.Arbitrary ) = tr.result( None, "", "", Pad(Some(os.temp.dir()), None) )
      tr.arbitrary("Create Temp Dir", action ).copy( actionDescription = Some("os.temp.dir()") )

    val PerformBackup =
      def action( prior : Pad, thisStep : tr.Arbitrary ) =
        val tmpDir = prior.tmpDir.getOrElse( throw new Exception("Failed to find expected backup tmpDir in carryforward. Cannot perform backup.") )
        val backupFile = tmpDir / backupFileName
        val parsedCommand = computeDoBackupParsedCommand( args, backupFile ) // e.g. List("postgres-dump-all-to-file", backupFile.toString)        
        def carryForward( prior : Pad, exitCode : Int, stepIn : String, stepOut : String ) = prior.copy(backupFile=Some(backupFile))
        tr.arbitraryExec( prior, thisStep, parsedCommand, carryForward ).copy( notes = Try( s"Backup size: ${friendlyFileSize(os.size(backupFile))}" ).toOption )
      tr.arbitrary(s"Perform ${displayDbName} Backup", action).copy( actionDescription = Some( doBackupActionDescription ) )

    val CopyBackupToStorage =
      def action( prior : Pad, thisStep : tr.Arbitrary ) =
        val backupFile = prior.backupFile.getOrElse( throw new Exception("Failed to find expected backupFile in carryforward. Cannot copy backup to storage.") )
        if isRcloneDest then
          val tmpResult = tr.arbitraryExec( prior, thisStep, List("rclone","mkdir",fullDestPath), tr.carryPrior )
          if tmpResult.exitCode == Some(0) then
            tr.arbitraryExec( prior, thisStep, List("rclone","copy",backupFile.toString,fullDestPath ), tr.carryPrior )
          else
            tmpResult
        else
          val tmpResult = tr.arbitraryExec( prior, thisStep, List("mkdir","-p",fullDestPath), tr.carryPrior )
          if tmpResult.exitCode == Some(0) then
            tr.arbitraryExec( prior, thisStep, List("cp",backupFile.toString,fullDestPath ), tr.carryPrior )
          else
            tmpResult
      tr.arbitrary("Copy backup to storage", action ).copy( actionDescription = Some( s"'rclone mkdir' then 'rclone copy' or else 'mkdir' then 'cp'" ) )

    // cleanups
    val RemoveLocalBackup =
      def action( prior : Pad, thisStep : tr.Arbitrary ) =
        val target = prior.backupFile.getOrElse( throw new Exception("No backup file recorded. Could not remove backup file to clean up.") )
        os.remove( target )
        tr.Result.emptyWithCarryForward(prior)
      tr.arbitrary("Remove temporary local backup.", action ).copy( actionDescription = Some( s"os.remove( <backup-file> )" ) )

    val task = new tr.Task:
      val name = s"Backup ${displayDbName}, all databases"
      val init = Pad()
      val sequential = List(
        EnsureRcloneIfNecessary,
        CreateTempDir,
        PerformBackup,
        CopyBackupToStorage,
      )
      val bestAttemptCleanups = List(
        RemoveLocalBackup
      )
    end task

    val reporters = TaskRunner.Reporters.default()
    tr.runAndReport(task, reporters)
    println(s"Backup of ${displayDbName} and reporting of results complete.")
