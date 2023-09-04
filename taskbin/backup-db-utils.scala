//> using scala "3.3.0"
//> using dep "com.mchange::mchange-sysadmin-scala:0.0.4"
//> using dep "com.lihaoyi::os-lib:0.9.1"

import scala.collection.*
import com.mchange.sysadmin.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

abstract class BackupDbRunner:
  def extractHostname( args : Array[String] ) : String
  def extractDestpath( args : Array[String] ) : String
  
  def computeDoBackupParsedCommand( args : Array[String], backupFilePath : os.Path ) : List[String]

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
        if isRcloneDest then
          tr.arbitraryExec( prior, thisStep, List("rclone", "--version"), tr.carryPrior )
        else
          tr.Result.zeroWithCarryForward(prior)
      tr.arbitrary(s"Ensure availability of rclone, if necessary (destpath '${destpath}')", action )

    val CreateTempDir =
      def action( prior : Pad, thisStep : tr.Arbitrary ) = tr.result( None, "", "", Pad(Some(os.temp.dir()), None) )
      tr.arbitrary("Create Temp Dir", action )

    val PerformBackup =
      def action( prior : Pad, thisStep : tr.Arbitrary ) =
        val tmpDir = prior.tmpDir.getOrElse( throw new Exception("Failed to find expected backup tmpDir in carryforward. Cannot perform backup.") )
        val backupFile = tmpDir / backupFileName
        val parsedCommand = computeDoBackupParsedCommand( args, backupFile ) // e.g. List("postgres-dump-all-to-file", backupFile.toString)        
        def carryForward( prior : Pad, exitCode : Int, stepIn : String, stepOut : String ) = prior.copy(backupFile=Some(backupFile))
        tr.arbitraryExec( prior, thisStep, parsedCommand, carryForward )
      tr.arbitrary(s"Perform ${displayDbName} Backup", action)

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
      tr.arbitrary("Copy backup to storage", action )

    // cleanups
    val RemoveLocalBackup =
      def action( prior : Pad, thisStep : tr.Arbitrary ) =
        os.remove( prior.backupFile.getOrElse( throw new Exception("No backup file recorded. Could not remove backup file to clean up.") ) )
        tr.Result.emptyWithCarryForward(prior)
      tr.arbitrary("Remove temporary local backup.", action )

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

    val reporters = TaskRunner.Reporters.default("sysadmin@mchange.com","sysadmin@mchange.com")
    tr.runAndReport(task, reporters)
    println(s"Backup of ${displayDbName} and reporting of results complete.")
