package model

import java.util.UUID

import org.joda.time.DateTime
import play.api.libs.json.Json

case class Jack(private val id: String = UUID.randomUUID().toString, firstName: String, lastName: String) {
  def getId = this.id
}

object Jack {

  implicit val format = Json.format[Jack]
}


case class Station(name: String, crsCode: String)

object Station {
  implicit val format = Json.format[Station]
}

case class TrainService(from: Station, to: Station)

object TrainService {
  implicit val format = Json.format[TrainService]
}

case class TubeLine(name: String, id: String)

object TubeLine {
  implicit val format = Json.format[TubeLine]
}

case class MeansOfTransportation(tubeLines: Seq[TubeLine], trainService: Seq[TrainService])

object MeansOfTransportation {
  implicit val format = Json.format[MeansOfTransportation]
}


case class TimeOfDay(hour: Int, min: Int)

object TimeOfDay {

  def plusMinutes(mins: Int) = TimeOfDay

  implicit val format = Json.format[TimeOfDay]
}


case class Journey(recurring: Boolean, meansOfTransportation: MeansOfTransportation, startsAt: TimeOfDay, durationInMin: Int)

object Journey {
  implicit val format = Json.format[Journey]
}


case class Email(from: String, to: String)

object Email {
  implicit val format = Json.format[Email]
}

/**
 *
 * @param alert alert to generate
 * @param journey journey to check
 * @param id record id
 * @param active
 * @param onlyOn indicates the date on the job must be executed - it cannot be a recurring one
 */
case class Job(alert: Email,
               journey: Journey,
               private val id: String = UUID.randomUUID().toString,
               active: Boolean = true,
               onlyOn: Option[DateTime] = None) {
  def getId = this.id
}

object Job {
  implicit val format = Json.format[Job]
}


case class RunningJob(private val id: String = UUID.randomUUID().toString,from: TimeOfDay, to:TimeOfDay, alertSent: Boolean = false, recussing: Boolean = true, jobId: String){
  def getId = this.id
}

object RunningJob {

//  def fromJob(job: Job) = RunningJob()

  implicit val format = Json.format[RunningJob]
}

case class EmailAlert(email: Email, persisted: Option[DateTime], sent: Option[DateTime])

case class JobForJack(runFrom: Int, runTill: Int, alertSent: Boolean, recurring: Boolean)

object EmailAlert {
  implicit val format = Json.format[EmailAlert]
}

object JobForJack {
  implicit val format = Json.format[JobForJack]
}