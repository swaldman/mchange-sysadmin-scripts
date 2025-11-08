//> using file ../project.scala

import scala.collection.immutable
import com.mchange.sysadmin.taskrunner.*
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

    val taskRunner = TaskRunner[Pad]
    import taskRunner.*

    // sequential
    val EnsureRcloneIfNecessary =
      Step.Arbitrary(s"Ensure availability of rclone, if necessary", actionDescription = Some("rclone --version") ): ( prior, thisStep ) =>
        val out = if isRcloneDest then
          arbitraryExec( prior, thisStep, List("rclone", "--version"), carryPrior )
        else
          Step.Result.zeroWithCarryForward(prior)
        out.copy( notes = Some(s"destpath: ${destpath}") )

    val CreateTempDir =
      Step.Arbitrary("Create Temp Dir", actionDescription = Some("os.temp.dir()") ): ( prior, thisStep ) =>
        Step.Result.onward( Pad(Some(os.temp.dir()), None) )

    val PerformBackup =
      Step.Arbitrary(s"Perform ${displayDbName} Backup", actionDescription = Some( doBackupActionDescription )): ( prior, thisStep ) =>
        val tmpDir = prior.tmpDir.getOrElse( abortUnexpectedPriorState("Failed to find expected backup tmpDir in carryforward. Cannot perform backup.") )
        val backupFile = tmpDir / backupFileName
        val parsedCommand = computeDoBackupParsedCommand( args, backupFile ) // e.g. List("postgres-dump-all-to-file", backupFile.toString)        
        def carryForward( prior : Pad, exitCode : Int, stepIn : String, stepOut : String ) = prior.copy(backupFile=Some(backupFile))
        arbitraryExec( prior, thisStep, parsedCommand, carryForward ).withNotes( s"Backup size: ${friendlyFileSize(os.size(backupFile))}" )


    val CopyBackupToStorage =
      Step.Arbitrary("Copy backup to storage", actionDescription = Some( s"'rclone mkdir' then 'rclone copy' or else 'mkdir' then 'cp'" ) ): ( prior, thisStep ) =>
        val backupFile = prior.backupFile.getOrElse( abortUnexpectedPriorState("Failed to find expected backupFile in carryforward. Cannot copy backup to storage.") )
        if isRcloneDest then
          val tmpResult = arbitraryExec( prior, thisStep, List("rclone","mkdir",fullDestPath), carryPrior )
          if tmpResult.exitCode == Some(0) then
            arbitraryExec( prior, thisStep, List("rclone","copy",backupFile.toString,fullDestPath ), carryPrior )
          else
            tmpResult
        else
          val tmpResult = arbitraryExec( prior, thisStep, List("mkdir","-p",fullDestPath), carryPrior )
          if tmpResult.exitCode == Some(0) then
            arbitraryExec( prior, thisStep, List("cp",backupFile.toString,fullDestPath ), carryPrior )
          else
            tmpResult

    // cleanups
    val RemoveLocalBackup =
      Step.Arbitrary("Remove temporary local backup.", actionDescription = Some( s"os.remove( <backup-file> )" ) ): ( prior, thisStep ) =>
        val target = prior.backupFile.getOrElse( abortUnexpectedPriorState("No backup file recorded. Could not remove backup file to clean up.") )
        os.remove( target )
        Step.Result.onward(prior)

    val task = new Task:
      val name = s"Backup ${displayDbName}, all databases"
      val init = Pad()
      val bestEffortSetups = Set.empty
      val sequential = List(
        EnsureRcloneIfNecessary,
        CreateTempDir,
        PerformBackup,
        CopyBackupToStorage,
      )
      val bestEffortFollowups = Set(
        RemoveLocalBackup
      )
    end task

    val reporters = Reporters.default()
    runAndReport(task, reporters)
    println(s"Backup of ${displayDbName} and reporting of results complete.")
