/*
 * Copyright 2016 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.seriestype

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy.{Image, Series}
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.storage.StorageProtocol.{DatasetAdded, GetDataset}
import se.nimsa.sbx.util.ExceptionCatching

import scala.concurrent.Future

class SeriesTypeUpdateActor(implicit val timeout: Timeout) extends Actor with ExceptionCatching {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val storageService = context.actorSelection("../../StorageService")
  val metaDataService = context.actorSelection("../../MetaDataService")
  val seriesTypeService = context.parent

  val seriesToUpdate = scala.collection.mutable.Set.empty[Long]
  val seriesBeingUpdated = scala.collection.mutable.Set.empty[Long]

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetAdded])
  }

  log.info("Series type update service started")

  def receive = LoggingReceive {

    case UpdateSeriesTypesForSeries(seriesId) =>
      addToUpdateQueueIfNotPresent(seriesId)
      self ! PollSeriesTypesUpdateQueue

    case UpdateSeriesTypesForAllSeries =>
      getAllSeries.map { allSeries =>
        self ! AddToUpdateQueue(allSeries.map(_.id))
      }

    case AddToUpdateQueue(seriesIds) =>
      seriesIds.map(addToUpdateQueueIfNotPresent)
      self ! PollSeriesTypesUpdateQueue

    case GetUpdateSeriesTypesRunningStatus =>
      sender ! UpdateSeriesTypesRunningStatus(seriesToUpdate.nonEmpty || seriesBeingUpdated.nonEmpty)

    case MarkSeriesAsProcessed(seriesId) =>
      seriesBeingUpdated -= seriesId

    case DatasetAdded(image, overwrite) =>
      self ! UpdateSeriesTypesForSeries(image.seriesId)

    case PollSeriesTypesUpdateQueue => pollSeriesTypesUpdateQueue()
  }

  def addToUpdateQueueIfNotPresent(seriesId: Long) =
    seriesToUpdate.add(seriesId)

  def pollSeriesTypesUpdateQueue() =
    if (seriesToUpdate.nonEmpty)
      nextSeriesNotBeingUpdated().foreach { seriesId =>
        val marked = markSeriesAsBeingUpdated(seriesId)
        if (marked)
          getSeries(seriesId).flatMap(_.map(series =>
            updateSeriesTypesForSeries(series))
            .getOrElse(Future.failed(new IllegalArgumentException(s"Series with id $seriesId not found"))))
            .onComplete {
              _ => self ! MarkSeriesAsProcessed(seriesId)
            }
      }

  def nextSeriesNotBeingUpdated() = seriesToUpdate.find(!seriesBeingUpdated.contains(_))

  def markSeriesAsBeingUpdated(seriesId: Long) =
    if (seriesToUpdate.remove(seriesId))
      seriesBeingUpdated.add(seriesId)
    else
      false

  def updateSeriesTypesForSeries(series: Series) = {
    val futureImageMaybe = removeAllSeriesTypesForSeries(series)
      .flatMap(u => getImageForSeries(series))

    futureImageMaybe.flatMap {
      case Some(image) =>
        val futureDatasetMaybe = storageService.ask(GetDataset(image, withPixelData = false)).mapTo[Option[Attributes]]

        val updateSeriesTypes = futureDatasetMaybe.flatMap(_.map(dataset => handleLoadedDataset(dataset, series)).getOrElse(Future.successful(Seq.empty)))

        updateSeriesTypes onComplete {
          _ => self ! PollSeriesTypesUpdateQueue
        }

        updateSeriesTypes

      case None =>
        self ! PollSeriesTypesUpdateQueue
        Future.successful(Seq.empty)
    }
  }

  def handleLoadedDataset(dataset: Attributes, series: Series): Future[Seq[Boolean]] = {
    getSeriesTypes.flatMap(allSeriesTypes =>
      updateSeriesTypesForDataset(dataset, series, allSeriesTypes))
  }

  def updateSeriesTypesForDataset(dataset: Attributes, series: Series, allSeriesTypes: Seq[SeriesType]): Future[Seq[Boolean]] = {
    Future.sequence(
      allSeriesTypes.map { seriesType =>
        evaluateSeriesTypeForSeries(seriesType, dataset, series)
      })
  }

  def evaluateSeriesTypeForSeries(seriesType: SeriesType, dataset: Attributes, series: Series): Future[Boolean] = {
    val futureRules = getSeriesTypeRules(seriesType)

    val futureRuleEvals = futureRules.flatMap(rules =>
      Future.sequence(
        rules.map(rule =>
          evaluateRuleForSeries(rule, dataset, series))))

    futureRuleEvals.flatMap(ruleEvals =>
      if (ruleEvals.contains(true))
        addSeriesTypeForSeries(seriesType, series).map(u => true)
      else
        Future.successful(false))
  }

  def evaluateRuleForSeries(rule: SeriesTypeRule, dataset: Attributes, series: Series): Future[Boolean] = {
    val futureAttributes = getSeriesTypeRuleAttributes(rule)

    futureAttributes.map(attributes =>
      attributes.foldLeft(true) { (acc, attribute) =>
        if (!acc) {
          false // A previous attribute already failed matching the dataset so no need to evaluate more attributes
        } else {
          evaluateRuleAttributeForToSeries(attribute, dataset, series)
        }
      })
  }

  def evaluateRuleAttributeForToSeries(attribute: SeriesTypeRuleAttribute, dataset: Attributes, series: Series): Boolean = {
    val attrs = attribute.tagPath.map(pathString => {
      try {
        val pathTags = pathString.split(",").map(_.toInt)
        pathTags.foldLeft(dataset)((nested, tag) => nested.getNestedDataset(tag))
      } catch {
        case e: Exception =>
          dataset
      }
    }).getOrElse(dataset)
    DicomUtil.concatenatedStringForTag(attrs, attribute.tag) == attribute.values
  }

  def getSeries(seriesId: Long): Future[Option[Series]] =
    metaDataService.ask(GetSingleSeries(seriesId)).mapTo[Option[Series]]

  def getAllSeries: Future[Seq[Series]] =
    metaDataService.ask(GetAllSeries).mapTo[SeriesCollection].map(_.series)

  def getImageForSeries(series: Series): Future[Option[Image]] =
    metaDataService.ask(GetImages(0, 1, series.id)).mapTo[Images]
      .map(_.images.headOption)

  def getSeriesTypes: Future[Seq[SeriesType]] =
    seriesTypeService.ask(GetSeriesTypes).mapTo[SeriesTypes].map(_.seriesTypes)

  def getSeriesTypeRules(seriesType: SeriesType): Future[Seq[SeriesTypeRule]] =
    seriesTypeService.ask(GetSeriesTypeRules(seriesType.id)).mapTo[SeriesTypeRules].map(_.seriesTypeRules)

  def getSeriesTypeRuleAttributes(seriesTypeRule: SeriesTypeRule): Future[Seq[SeriesTypeRuleAttribute]] =
    seriesTypeService.ask(GetSeriesTypeRuleAttributes(seriesTypeRule.id)).mapTo[SeriesTypeRuleAttributes].map(_.seriesTypeRuleAttributes)

  def addSeriesTypeForSeries(seriesType: SeriesType, series: Series): Future[SeriesTypeAddedToSeries] =
    seriesTypeService.ask(AddSeriesTypeToSeries(seriesType, series)).mapTo[SeriesTypeAddedToSeries]

  def removeAllSeriesTypesForSeries(series: Series): Future[SeriesTypesRemovedFromSeries] =
    seriesTypeService.ask(RemoveSeriesTypesFromSeries(series.id)).mapTo[SeriesTypesRemovedFromSeries]

}

object SeriesTypeUpdateActor {
  def props(timeout: Timeout): Props = Props(new SeriesTypeUpdateActor()(timeout))
}
