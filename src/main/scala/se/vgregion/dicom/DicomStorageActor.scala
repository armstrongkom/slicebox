package se.vgregion.dicom

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol._
import DicomHierarchy._
import DicomPropertyValue._
import DicomUtil._
import se.vgregion.dicom.DicomProtocol.DatasetReceived
import se.vgregion.util.ExceptionCatching
import org.dcm4che3.data.Attributes.Visitor
import scala.collection.mutable.ListBuffer
import org.dcm4che3.data.Keyword
import org.dcm4che3.util.TagUtils

class DicomStorageActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  setupDb()

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetReceived])
    context.system.eventStream.subscribe(context.self, classOf[FileReceived])
  }

  def receive = LoggingReceive {

    case FileReceived(path) =>
      val dataset = loadDataset(path, true)
      if (dataset != null)
        if (checkSopClass(dataset)) {
          val image = storeDataset(dataset)
          log.info("Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))
        } else
          log.info(s"Received file with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
      else
        log.info(s"File $path is not a DICOM file")

    case DatasetReceived(dataset) =>
      val image = storeDataset(dataset)
      log.info("Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))

    case AddDataset(dataset) =>
      catchAndReport {
        val image = storeDataset(dataset)
        sender ! ImageAdded(image)
      }

    case msg: MetaDataUpdate =>

      catchAndReport {

        msg match {

          case DeleteImage(imageId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFileForImage(imageId).toList
              dao.deleteImage(imageId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

          case DeleteSeries(seriesId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFilesForSeries(Seq(seriesId))
              dao.deleteSeries(seriesId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

          case DeleteStudy(studyId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFilesForStudy(studyId)
              dao.deleteStudy(studyId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

          case DeletePatient(patientId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFilesForPatient(patientId)
              dao.deletePatient(patientId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

        }
      }

    case GetAllImageFiles =>
      catchAndReport {
        db.withSession { implicit session =>
          sender ! ImageFiles(dao.imageFiles)
        }
      }

    case GetImageFile(imageId) =>
      catchAndReport {
        db.withSession { implicit session =>
          dao.imageFileForImage(imageId) match {
            case Some(imageFile) =>
              sender ! imageFile
            case None =>
              throw new IllegalArgumentException(s"No file found for image $imageId")
          }
        }
      }

    case GetImageAttributes(imageId) =>
      catchAndReport {
        db.withSession { implicit session =>
          dao.imageFileForImage(imageId) match {
            case Some(imageFile) =>
              sender ! readImageAttributes(imageFile.fileName.value)
            case None =>
              throw new IllegalArgumentException(s"No file found for image $imageId")
          }
        }
      }

    case msg: MetaDataQuery => catchAndReport {
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter) =>
          db.withSession { implicit session =>
            sender ! Patients(dao.patients(startIndex, count, orderBy, orderAscending, filter))
          }

        case GetStudies(startIndex, count, patientId) =>
          db.withSession { implicit session =>
            sender ! Studies(dao.studiesForPatient(startIndex, count, patientId))
          }

        case GetSeries(startIndex, count, studyId) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(dao.seriesForStudy(startIndex, count, studyId))
          }

        case GetImages(seriesId) =>
          db.withSession { implicit session =>
            sender ! Images(dao.imagesForSeries(seriesId))
          }

        case GetImageFilesForSeries(seriesIds) =>
          db.withSession { implicit session =>
            sender ! ImageFiles(dao.imageFilesForSeries(seriesIds))
          }

        case GetImageFilesForStudies(studyIds) =>
          db.withSession { implicit session =>
            sender ! ImageFiles(dao.imageFilesForStudies(studyIds))
          }

        case GetImageFilesForPatients(patientIds) =>
          db.withSession { implicit session =>
            sender ! ImageFiles(dao.imageFilesForPatients(patientIds))
          }
      }
    }

  }

  def storeDataset(dataset: Attributes): Image = {
    val name = fileName(dataset)
    val storedPath = storage.resolve(name)

    db.withSession { implicit session =>
      val patient = datasetToPatient(dataset)
      val dbPatient = dao.patientByNameAndID(patient)
        .getOrElse(dao.insert(patient))

      val study = datasetToStudy(dataset)
      val dbStudy = dao.studyByUid(study)
        .getOrElse(dao.insert(study.copy(patientId = dbPatient.id)))

      val equipment = datasetToEquipment(dataset)
      val dbEquipment = dao.equipmentByManufacturerAndStationName(equipment)
        .getOrElse(dao.insert(equipment))

      val frameOfReference = datasetToFrameOfReference(dataset)
      val dbFrameOfReference = dao.frameOfReferenceByUid(frameOfReference)
        .getOrElse(dao.insert(frameOfReference))

      val series = datasetToSeries(dataset)
      val dbSeries = dao.seriesByUid(series)
        .getOrElse(dao.insert(series.copy(
          studyId = dbStudy.id,
          equipmentId = dbEquipment.id,
          frameOfReferenceId = dbFrameOfReference.id)))

      val image = datasetToImage(dataset)
      val dbImage = dao.imageByUid(image)
        .getOrElse(dao.insert(image.copy(seriesId = dbSeries.id)))

      val imageFile = ImageFile(dbImage.id, FileName(name))
      val dbImageFile = dao.imageFileByFileName(imageFile)
        .getOrElse(dao.insert(imageFile))

      saveDataset(dataset, storedPath)

      dbImage
    }
  }

  def fileName(dataset: Attributes): String = dataset.getString(Tag.SOPInstanceUID)

  def deleteFromStorage(imageFiles: Seq[ImageFile]): Unit = imageFiles foreach (deleteFromStorage(_))
  def deleteFromStorage(imageFile: ImageFile): Unit = deleteFromStorage(storage.resolve(imageFile.fileName.value))
  def deleteFromStorage(filePath: Path): Unit = {
    Files.delete(filePath)
    log.info("Deleted file " + filePath)
  }

  def readImageAttributes(fileName: String): ImageAttributes = {
    val filePath = storage.resolve(fileName)
    val dataset = loadDataset(filePath, false)
    ImageAttributes(readImageAttributes(dataset, 0, ""))
  }

  def readImageAttributes(dataset: Attributes, depth: Int, path: String): List[ImageAttribute] = {
    val attributesBuffer = ListBuffer.empty[ImageAttribute]
    if (dataset != null) {
      dataset.accept(new Visitor() {
        override def visit(attrs: Attributes, tag: Int, vr: VR, value: AnyRef): Boolean = {
          val group = TagUtils.toHexString(TagUtils.groupNumber(tag)).substring(4)
          val element = TagUtils.toHexString(TagUtils.elementNumber(tag)).substring(4)
          val name = Keyword.valueOf(tag)
          val vrName = vr.name
          val length = lengthOf(attrs.getBytes(tag))
          val multiplicity = multiplicityOf(attrs.getStrings(tag))
          val content = toSingleString(attrs.getStrings(tag))
          attributesBuffer += ImageAttribute(group, element, vrName, length, multiplicity, depth, path, name, content)
          if (vr == VR.SQ) {
            val nextPath = if (path.isEmpty()) name else path + '/' + name
            attributesBuffer ++= readImageAttributes(attrs.getNestedDataset(tag), depth + 1, nextPath)
          }
          true
        }
      }, false)
    }
    attributesBuffer.toList
  }

  def lengthOf(bytes: Array[Byte]) =
    if (bytes == null)
      0
    else
      bytes.length

  def multiplicityOf(strings: Array[String]) =
    if (strings == null)
      0
    else
      strings.length

  def toSingleString(strings: Array[String]) =
    if (strings == null || strings.length == 0)
      ""
    else if (strings.length == 1)
      strings(0)
    else
      "[" + strings.tail.foldLeft(strings.head)((single, s) => single + "," + s) + "]"

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

}

object DicomStorageActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DicomStorageActor(dbProps, storage))
}
